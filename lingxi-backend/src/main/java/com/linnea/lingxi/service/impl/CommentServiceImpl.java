package com.linnea.lingxi.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.linnea.lingxi.common.cursor.TimeIdCursor;
import com.linnea.lingxi.common.exception.BusinessException;
import com.linnea.lingxi.common.exception.ErrorCode;
import com.linnea.lingxi.common.exception.SystemException;
import com.linnea.lingxi.common.idempotent.IdempotentGuard;
import com.linnea.lingxi.dto.CommentQueryDTO;
import com.linnea.lingxi.dto.CreateCommentDTO;
import com.linnea.lingxi.dto.ListQueryDTO;
import com.linnea.lingxi.entity.Comment;
import com.linnea.lingxi.entity.Post;
import com.linnea.lingxi.entity.User;
import com.linnea.lingxi.mapper.CommentMapper;
import com.linnea.lingxi.mapper.PostMapper;
import com.linnea.lingxi.mapper.UserMapper;
import com.linnea.lingxi.service.CommentService;
import com.linnea.lingxi.service.NotificationService;
import com.linnea.lingxi.vo.CommentPageVO;
import com.linnea.lingxi.vo.CommentReplyCountVO;
import com.linnea.lingxi.vo.CommentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 评论业务实现：发表评论（含 root_id 回填与校验）+ 两级列表键集分页。
 *
 * <p>root_id 一致性由三条规则保证：
 * 一级评论插入后在同一事务内把 root_id 回填为自身ID；
 * 回复的 root_id 一律继承父评论的 root_id，不接受前端传入；
 * 父评论必须属于同一动态，否则拒绝写入，避免把评论挂到别的动态的讨论串下。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    /** 一级评论的 parent_id 约定值：DB 用 0 而不是 NULL，便于 where 条件与 root_id 回填判断。 */
    private static final long ROOT_COMMENT_PARENT_ID = 0L;

    /** 无被回复人的 reply_user_id 约定值。 */
    private static final long NO_REPLY_USER_ID = 0L;

    /** 一级评论的 root_id 初始值：插入时先占位，随后同事务回填为自身ID。 */
    private static final long ROOT_ID_PLACEHOLDER = 0L;

    /** 评论列表默认每页条数。 */
    private static final int DEFAULT_PAGE_SIZE = 10;

    /** 列表查询每页上限，与 ListQueryDTO 的 @Max 保持一致：服务层再兜一次，防止校验被绕过时打库。 */
    private static final int MAX_LIST_PAGE_SIZE = 30;

    /** 发表评论的幂等场景标识，参与拼 Redis 键，与发布动态的键空间隔离。 */
    private static final String IDEMPOTENT_SCENE_COMMENT = "comment:create";

    /** 评论数据访问。 */
    private final CommentMapper commentMapper;

    /** 动态数据访问：评论前校验动态仍存在。 */
    private final PostMapper postMapper;

    /** 用户数据访问：组装作者与被回复人昵称。 */
    private final UserMapper userMapper;

    /** 幂等栅栏：同一 requestId 的重复评论只落一条。 */
    private final IdempotentGuard idempotentGuard;

    /** 通知业务服务：评论通知动态作者，回复通知被回复人。 */
    private final NotificationService notificationService;

    /**
     * 对指定动态发表评论或回复。
     *
     * <p>幂等：带 requestId 时同一用户同一键只落一条评论，重复提交返回首次结果；
     * 不带 requestId 则不做去重（与发布动态口径一致）。</p>
     *
     * @param createCommentInfo 评论请求参数
     * @param postId            动态ID
     * @param userId            评论人ID
     * @return 新建评论的对外信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommentVO createComment(CreateCommentDTO createCommentInfo, Long postId, Long userId) {
        // 步骤1：身份兜底校验，正常由认证过滤器保证非空
        if (userId == null) {
            log.warn("发表评论被拒绝, 缺少评论人身份, postId={}", postId);
            throw new BusinessException(ErrorCode.COMMON_UNAUTHORIZED);
        }
        // 步骤2：清洗正文，@NotBlank 拦不住纯空白字符串
        String content = createCommentInfo.getContent() == null ? null : createCommentInfo.getContent().trim();
        if (!StringUtils.hasText(content)) {
            log.warn("发表评论被拒绝, 正文为纯空白, postId={}, userId={}", postId, userId);
            throw new BusinessException(ErrorCode.COMMON_PARAM_ERROR, "评论正文不能为空");
        }
        log.info("发表评论开始, postId={}, userId={}, parentId={}, contentLength={}",
                postId, userId, createCommentInfo.getParentId(), content.length());
        // 步骤3：幂等键非空时抢占，抢占失败说明是重复提交，直接返回首次结果
        String requestId = StringUtils.hasText(createCommentInfo.getRequestId())
                ? createCommentInfo.getRequestId().trim() : null;
        if (requestId != null
                && !idempotentGuard.tryAcquire(IDEMPOTENT_SCENE_COMMENT, requestId, userId)) {
            return resolveIdempotentResult(requestId, userId);
        }
        // 步骤4：业务执行失败要释放幂等键，否则客户端在 TTL 内无法重试
        try {
            CommentVO createdCommentInfo = doCreateComment(content, createCommentInfo.getParentId(), postId, userId);
            // 步骤5：回填幂等结果，重复请求据此返回同一条评论
            if (requestId != null) {
                idempotentGuard.bindResult(IDEMPOTENT_SCENE_COMMENT, requestId, userId,
                        String.valueOf(createdCommentInfo.getCommentId()));
            }
            return createdCommentInfo;
        } catch (RuntimeException createException) {
            if (requestId != null) {
                idempotentGuard.release(IDEMPOTENT_SCENE_COMMENT, requestId, userId);
            }
            log.error("发表评论失败, postId={}, userId={}", postId, userId, createException);
            throw createException;
        }
    }

    /**
     * 校验动态与父评论后落库并组装返回对象，拆出来便于外层统一处理幂等键释放。
     *
     * @param content       已清洗的正文
     * @param parentIdInput 父评论ID，可为 null 或 0，均表示一级评论
     * @param postId        所属动态ID
     * @param userId        评论人ID
     * @return 新建评论的对外信息，replyCount 固定为 0（新建评论尚无回复）
     */
    private CommentVO doCreateComment(String content, Long parentIdInput, Long postId, Long userId) {
        // 步骤1：校验动态存在且未删除
        Post existedPost = postId == null ? null : postMapper.selectById(postId);
        if (existedPost == null) {
            log.warn("发表评论被拒绝, 动态不存在或已删除, postId={}, userId={}", postId, userId);
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }
        long parentId = parentIdInput == null ? ROOT_COMMENT_PARENT_ID : parentIdInput;
        // 步骤2：解析父评论，确定讨论串根ID与被回复人
        long rootId = ROOT_ID_PLACEHOLDER;
        long replyUserId = NO_REPLY_USER_ID;
        if (parentId > ROOT_COMMENT_PARENT_ID) {
            Comment parentComment = commentMapper.selectById(parentId);
            if (parentComment == null) {
                log.warn("发表评论被拒绝, 父评论不存在或已删除, parentId={}, userId={}", parentId, userId);
                throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
            }
            // 步骤2.1：父评论必须属于同一动态，防止跨动态挂载讨论串
            if (!postId.equals(parentComment.getPostId())) {
                log.warn("发表评论被拒绝, 父评论不属于该动态, postId={}, parentId={}, userId={}",
                        postId, parentId, userId);
                throw new BusinessException(ErrorCode.COMMENT_PARENT_MISMATCH);
            }
            // 步骤2.2：回复继承父评论的 root_id；父评论若为历史遗留的 root_id=0，用其自身ID兜底
            long parentRootId = parentComment.getRootId() == null ? ROOT_ID_PLACEHOLDER : parentComment.getRootId();
            rootId = parentRootId > ROOT_ID_PLACEHOLDER ? parentRootId : parentComment.getId();
            replyUserId = parentComment.getUserId();
        }
        // 步骤3：落库，一级评论 root_id 先占 0，供步骤4 回填
        Comment newComment = new Comment();
        newComment.setPostId(postId);
        newComment.setUserId(userId);
        newComment.setParentId(parentId);
        newComment.setRootId(rootId);
        newComment.setReplyUserId(replyUserId);
        newComment.setContent(content);
        commentMapper.insert(newComment);
        if (newComment.getId() == null) {
            throw new SystemException("评论插入后未返回主键, postId=" + postId);
        }
        // 步骤4：一级评论同事务回填 root_id = 自身ID，保证讨论串有根；只更新 root_id 一列
        if (parentId == ROOT_COMMENT_PARENT_ID) {
            rootId = newComment.getId();
            int updatedRows = commentMapper.update(null, new LambdaUpdateWrapper<Comment>()
                    .set(Comment::getRootId, rootId)
                    .eq(Comment::getId, newComment.getId()));
            if (updatedRows == 0) {
                throw new SystemException("一级评论回填 root_id 失败, commentId=" + newComment.getId());
            }
            log.info("一级评论回填 root_id 完成, commentId={}, rootId={}", newComment.getId(), rootId);
        }
        // 步骤5：回读落库结果，create_time 由数据库默认值填充
        Comment createdComment = commentMapper.selectById(newComment.getId());
        if (createdComment == null) {
            throw new SystemException("评论插入后回读为空, commentId=" + newComment.getId());
        }
        // 步骤5.1：投递通知——一级评论通知动态作者，回复通知被回复人。
        // WHY 二选一而不是两处都发：回复同时通知「动态作者」和「被回复人」时，若两者是同一人会收到两条一模一样的提醒，
        // 拆开后每条通知只对应一个明确的「你被谁触达了」。
        if (parentId == ROOT_COMMENT_PARENT_ID) {
            notificationService.notifyPostCommented(postId, existedPost.getUserId(), newComment.getId(), userId);
        } else {
            notificationService.notifyCommentReplied(postId, replyUserId, newComment.getId(), userId);
        }
        // 步骤6：查询作者与被回复人资料并组装对外对象
        User author = userMapper.selectById(userId);
        User replyUser = replyUserId == NO_REPLY_USER_ID ? null : userMapper.selectById(replyUserId);
        CommentVO createdCommentInfo = CommentVO.from(createdComment, author, replyUser);
        createdCommentInfo.setReplyCount(0L);
        log.info("发表评论落库完成, commentId={}, postId={}, rootId={}, userId={}",
                createdCommentInfo.getCommentId(), postId, rootId, userId);
        return createdCommentInfo;
    }

    /**
     * 处理幂等命中：返回首次发表的那条评论。
     *
     * <p>幂等键值为占位符表示首个请求仍在处理（并发重复提交），此时既不能返回结果也不能放行去重复落库，
     * 只能按重复提交拒绝，让客户端稍后重试。</p>
     *
     * @param requestId 客户端幂等键
     * @param userId    评论人ID
     * @return 首次发表的评论信息
     */
    private CommentVO resolveIdempotentResult(String requestId, Long userId) {
        // 步骤1：读取幂等键当前值
        String resultValue = idempotentGuard.queryResult(IDEMPOTENT_SCENE_COMMENT, requestId, userId);
        // 步骤2：键已消失或仍在处理中，都按重复提交拒绝，避免同一次评论落两条
        if (resultValue == null || idempotentGuard.isProcessing(resultValue)) {
            log.warn("发表评论被拒绝, 相同幂等键处理中或结果已过期, userId={}", userId);
            throw new BusinessException(ErrorCode.COMMON_DUPLICATE_REQUEST);
        }
        // 步骤3：解析首次结果的评论ID并回查，评论已被删除时同样按重复提交拒绝
        Long existedCommentId;
        try {
            existedCommentId = Long.valueOf(resultValue);
        } catch (NumberFormatException numberFormatException) {
            log.warn("幂等键值非法, 按重复提交拒绝, userId={}", userId, numberFormatException);
            throw new BusinessException(ErrorCode.COMMON_DUPLICATE_REQUEST);
        }
        Comment existedComment = commentMapper.selectById(existedCommentId);
        if (existedComment == null) {
            log.warn("幂等命中但评论已删除, commentId={}, userId={}", existedCommentId, userId);
            throw new BusinessException(ErrorCode.COMMON_DUPLICATE_REQUEST);
        }
        // 步骤4：组装首次结果，作者与被回复人重新查询保证昵称头像最新；
        // replyCount 固定 0 与首次响应口径一致（幂等语义是「同一次请求返回同一份结果」）
        User author = userMapper.selectById(existedComment.getUserId());
        User replyUser = existedComment.getReplyUserId() == null
                || existedComment.getReplyUserId() == NO_REPLY_USER_ID
                ? null : userMapper.selectById(existedComment.getReplyUserId());
        CommentVO existedCommentInfo = CommentVO.from(existedComment, author, replyUser);
        existedCommentInfo.setReplyCount(0L);
        log.info("发表评论命中幂等, commentId={}, userId={}", existedCommentId, userId);
        return existedCommentInfo;
    }

    /**
     * 查询某个动态下的一级评论分页。
     *
     * @param postId           动态ID
     * @param commentQueryInfo 分页参数
     * @return 一级评论分页结果
     */
    @Override
    public CommentPageVO queryRootCommentPage(Long postId, CommentQueryDTO commentQueryInfo) {
        // 步骤1：校验动态存在，避免对不存在的动态返回空列表掩盖调用错误
        Post existedPost = postId == null ? null : postMapper.selectById(postId);
        if (existedPost == null) {
            log.warn("查询一级评论被拒绝, 动态不存在或已删除, postId={}", postId);
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }
        // 步骤2：归一化每页条数并解码游标
        int pageSize = resolvePageSize(commentQueryInfo);
        TimeIdCursor commentCursor = TimeIdCursor.decode(commentQueryInfo.getCursor());
        log.info("查询一级评论开始, postId={}, pageSize={}, hasCursor={}", postId, pageSize, commentCursor != null);
        // 步骤3：多取一条探测下一页
        List<CommentVO> commentRows = commentMapper.selectRootCommentList(postId,
                commentCursor == null ? null : commentCursor.getCreateTime(),
                commentCursor == null ? null : commentCursor.getId(),
                pageSize + 1);
        CommentPageVO commentPage = assembleCommentPage(commentRows, pageSize);
        // 步骤4：归一化作者与被回复人展示字段，保证与发表评论接口返回的字段口径一致
        normalizeCommentList(commentPage.getCommentList());
        // 步骤5：批量补齐各讨论串的回复数，避免逐条查询
        fillReplyCount(commentPage.getCommentList());
        log.info("查询一级评论完成, postId={}, itemCount={}, hasMore={}",
                postId, commentPage.getCommentList().size(), commentPage.getHasMore());
        return commentPage;
    }

    /**
     * 查询某个讨论串下的回复分页。
     *
     * @param rootCommentId    讨论串根评论ID
     * @param commentQueryInfo 分页参数
     * @return 回复分页结果
     */
    @Override
    public CommentPageVO queryReplyPage(Long rootCommentId, CommentQueryDTO commentQueryInfo) {
        // 步骤1：校验根评论存在，逻辑删除的讨论串不再返回回复
        Comment rootComment = rootCommentId == null ? null : commentMapper.selectById(rootCommentId);
        if (rootComment == null) {
            log.warn("查询讨论串回复被拒绝, 根评论不存在或已删除, rootCommentId={}", rootCommentId);
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }
        // 步骤2：归一化每页条数并解码游标
        int pageSize = resolvePageSize(commentQueryInfo);
        TimeIdCursor commentCursor = TimeIdCursor.decode(commentQueryInfo.getCursor());
        log.info("查询讨论串回复开始, rootCommentId={}, pageSize={}, hasCursor={}",
                rootCommentId, pageSize, commentCursor != null);
        // 步骤3：多取一条探测下一页
        List<CommentVO> replyRows = commentMapper.selectReplyList(rootCommentId,
                commentCursor == null ? null : commentCursor.getCreateTime(),
                commentCursor == null ? null : commentCursor.getId(),
                pageSize + 1);
        CommentPageVO replyPage = assembleCommentPage(replyRows, pageSize);
        // 步骤4：归一化展示字段，与发表评论接口保持一致（一级评论被回复人昵称为空串而非 null）
        normalizeCommentList(replyPage.getCommentList());
        log.info("查询讨论串回复完成, rootCommentId={}, itemCount={}, hasMore={}",
                rootCommentId, replyPage.getCommentList().size(), replyPage.getHasMore());
        return replyPage;
    }

    /**
     * 归一化评论列表的展示字段。
     *
     * <p>WHY 需要这一步：列表走自定义 XML 映射，联表昵称被 COALESCE 成空串（作者注销或昵称为空），
     * 而发表评论接口走 {@link CommentVO#from} 的兜底逻辑；不归一化会出现「刚发表返回空串、
     * 刷新列表变 null」的口径不一致，前端判空逻辑会被迫写两套。</p>
     *
     * @param commentList 当前页评论，可为空集合
     */
    private void normalizeCommentList(List<CommentVO> commentList) {
        // 步骤1：逐条补齐空值，集合为空时直接返回
        if (commentList == null || commentList.isEmpty()) {
            return;
        }
        for (CommentVO commentItem : commentList) {
            if (!StringUtils.hasText(commentItem.getAuthorNickname())) {
                commentItem.setAuthorNickname(CommentVO.DELETED_AUTHOR_NICKNAME);
            }
            if (commentItem.getAuthorAvatarUrl() == null) {
                commentItem.setAuthorAvatarUrl("");
            }
            if (commentItem.getReplyUserNickname() == null) {
                commentItem.setReplyUserNickname("");
            }
        }
        log.info("评论列表展示字段归一化完成, itemCount={}", commentList.size());
    }

    /**
     * 裁剪探测行并生成下一页游标。
     *
     * @param commentRows 含探测行的查询结果
     * @param pageSize    每页条数
     * @return 分页结果
     */
    private CommentPageVO assembleCommentPage(List<CommentVO> commentRows, int pageSize) {
        // 步骤1：多取的行数说明还有下一页
        boolean hasMore = commentRows.size() > pageSize;
        List<CommentVO> pageCommentList = hasMore ? new ArrayList<>(commentRows.subList(0, pageSize)) : commentRows;
        // 步骤2：只有确实存在下一页时才生成游标，末页返回 null 让前端停止翻页
        String nextCursor = null;
        if (hasMore && !pageCommentList.isEmpty()) {
            CommentVO lastComment = pageCommentList.get(pageCommentList.size() - 1);
            nextCursor = TimeIdCursor.encode(lastComment.getCreateTime(), lastComment.getCommentId());
        }
        // 步骤3：组装返回结构
        CommentPageVO commentPage = new CommentPageVO();
        commentPage.setCommentList(pageCommentList);
        commentPage.setNextCursor(nextCursor);
        commentPage.setHasMore(hasMore);
        // 步骤4：记录裁剪结果，排查「多取一条」探测与游标生成是否同步
        log.info("评论分页结果组装完成, pageSize={}, itemCount={}, hasMore={}",
                pageSize, pageCommentList.size(), hasMore);
        return commentPage;
    }

    /**
     * 批量填充一级评论的回复数。
     *
     * @param pageCommentList 当前页一级评论，可为空集合
     */
    private void fillReplyCount(List<CommentVO> pageCommentList) {
        // 步骤1：空页无需查询，同时避免 XML 中 IN () 的语法错误
        if (pageCommentList == null || pageCommentList.isEmpty()) {
            return;
        }
        // 步骤2：一次 GROUP BY 取回当前页所有根评论的回复数
        List<Long> rootIdList = pageCommentList.stream().map(CommentVO::getCommentId).toList();
        List<CommentReplyCountVO> replyCountList = commentMapper.selectReplyCountList(rootIdList);
        Map<Long, Long> replyCountMap = replyCountList.stream()
                .collect(Collectors.toMap(CommentReplyCountVO::getRootId, CommentReplyCountVO::getReplyCount));
        // 步骤3：无回复的根评论不参与分组，默认填 0
        pageCommentList.forEach(commentItem ->
                commentItem.setReplyCount(replyCountMap.getOrDefault(commentItem.getCommentId(), 0L)));
        log.info("一级评论回复数填充完成, rootCount={}, filledCount={}", rootIdList.size(), replyCountList.size());
    }

    /**
     * 解析每页条数，未传时使用默认值。
     *
     * @param commentQueryInfo 分页参数
     * @return 每页条数
     */
    private int resolvePageSize(CommentQueryDTO commentQueryInfo) {
        // 步骤1：DTO 已限制 1-30，这里只处理未传的情况
        int pageSize = commentQueryInfo.getSize() == null ? DEFAULT_PAGE_SIZE : commentQueryInfo.getSize();
        // 步骤2：记录最终条数，便于排查「参数已传但分页按默认值走」的问题
        log.info("评论分页条数解析完成, pageSize={}", pageSize);
        return pageSize;
    }

    /**
     * 删除评论（含其下全部回复）。
     *
     * @param commentId      评论ID
     * @param operatorUserId 当前登录用户ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteComment(Long commentId, Long operatorUserId) {
        // 步骤1：作者自删，必须校验归属
        removeCommentInternal(commentId, operatorUserId, false);
    }

    /**
     * 管理员删除任意评论（逻辑删除），连同其下全部回复一起删除。
     *
     * @param commentId      评论ID
     * @param operatorUserId 管理员ID，仅用于日志追溯
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeCommentByAdmin(Long commentId, Long operatorUserId) {
        // 步骤1：后台删除，跳过归属校验（是否管理员由控制器 @PreAuthorize 前置判定）
        removeCommentInternal(commentId, operatorUserId, true);
    }

    /**
     * 删除评论的统一实现：作者自删与后台删除共用同一套级联范围。
     *
     * @param commentId      评论ID
     * @param operatorUserId 操作者用户ID
     * @param asAdmin        true 表示后台删除，跳过归属校验
     */
    private void removeCommentInternal(Long commentId, Long operatorUserId, boolean asAdmin) {
        // 步骤1：身份兜底
        if (operatorUserId == null) {
            log.warn("删除评论被拒绝, 缺少操作者身份, commentId={}", commentId);
            throw new BusinessException(ErrorCode.COMMON_UNAUTHORIZED);
        }
        // 步骤2：回查评论，逻辑删除的查不到
        Comment existedComment = commentId == null ? null : commentMapper.selectById(commentId);
        if (existedComment == null) {
            log.warn("删除评论被拒绝, 评论不存在或已删除, commentId={}, userId={}, asAdmin={}",
                    commentId, operatorUserId, asAdmin);
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }
        // 步骤3：非管理员必须校验归属，只有作者能删
        if (!asAdmin && !operatorUserId.equals(existedComment.getUserId())) {
            log.warn("删除评论被拒绝, 非作者操作, commentId={}, ownerId={}, userId={}",
                    commentId, existedComment.getUserId(), operatorUserId);
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN);
        }
        log.info("删除评论开始, commentId={}, userId={}, asAdmin={}, ownerId={}, postId={}, rootId={}",
                commentId, operatorUserId, asAdmin, existedComment.getUserId(),
                existedComment.getPostId(), existedComment.getRootId());
        // 步骤4：取评论自身与全部后代ID，一次批量逻辑删除，避免留下孤儿回复
        List<Long> descendantIdList = commentMapper.selectDescendantIdList(commentId);
        if (descendantIdList.isEmpty()) {
            log.warn("删除评论失败, 未查到待删除节点, commentId={}, userId={}, asAdmin={}",
                    commentId, operatorUserId, asAdmin);
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }
        int deletedRows = commentMapper.softDeleteByIdList(descendantIdList);
        log.info("删除评论完成, commentId={}, userId={}, asAdmin={}, deletedRows={}, descendantCount={}",
                commentId, operatorUserId, asAdmin, deletedRows, descendantIdList.size());
    }

    /**
     * 分页查询我的评论。
     *
     * @param userId        目标用户ID
     * @param listQueryInfo 分页参数
     * @return 评论分页结果
     */
    @Override
    public CommentPageVO queryMyCommentPage(Long userId, ListQueryDTO listQueryInfo) {
        // 步骤1：身份兜底，未认证不允许查评论记录
        if (userId == null) {
            log.warn("查询我的评论被拒绝, 缺少用户身份");
            throw new BusinessException(ErrorCode.COMMON_UNAUTHORIZED);
        }
        // 步骤2：归一化条数并解码游标
        int pageSize = resolveListPageSize(listQueryInfo);
        TimeIdCursor commentCursor = TimeIdCursor.decode(listQueryInfo.getCursor());
        log.info("查询我的评论开始, userId={}, pageSize={}, hasCursor={}", userId, pageSize, commentCursor != null);
        // 步骤3：多取一条探测下一页，SQL 已过滤掉所属动态被删除的评论
        List<CommentVO> commentRows = commentMapper.selectUserCommentList(userId,
                commentCursor == null ? null : commentCursor.getCreateTime(),
                commentCursor == null ? null : commentCursor.getId(),
                pageSize + 1);
        CommentPageVO commentPage = assembleCommentPage(commentRows, pageSize);
        // 步骤4：展示字段归一化，口径与一级评论列表一致
        normalizeCommentList(commentPage.getCommentList());
        log.info("查询我的评论完成, userId={}, itemCount={}, hasMore={}",
                userId, commentPage.getCommentList().size(), commentPage.getHasMore());
        return commentPage;
    }

    /**
     * 归一化列表查询的每页条数。
     *
     * @param listQueryInfo 列表查询参数
     * @return 落在 [1, 30] 区间的条数，未传时取默认值
     */
    private int resolveListPageSize(ListQueryDTO listQueryInfo) {
        // 步骤1：未传走默认，传了则上下界兜底（Bean Validation 正常已拦住越界请求）
        Integer requestSize = listQueryInfo.getSize();
        int pageSize = requestSize == null
                ? DEFAULT_PAGE_SIZE
                : Math.max(1, Math.min(requestSize, MAX_LIST_PAGE_SIZE));
        // 步骤2：记录最终条数，便于排查「参数已传但按默认值分页」的问题
        log.info("列表分页条数解析完成, pageSize={}", pageSize);
        return pageSize;
    }
}
