package com.linnea.lingxi.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.linnea.lingxi.common.cursor.FeedCursor;
import com.linnea.lingxi.common.cursor.TimeIdCursor;
import com.linnea.lingxi.common.exception.BusinessException;
import com.linnea.lingxi.common.exception.ErrorCode;
import com.linnea.lingxi.common.exception.SystemException;
import com.linnea.lingxi.common.idempotent.IdempotentGuard;
import com.linnea.lingxi.dto.CreatePostDTO;
import com.linnea.lingxi.dto.FeedQueryDTO;
import com.linnea.lingxi.dto.ListQueryDTO;
import com.linnea.lingxi.dto.UpdatePostDTO;
import com.linnea.lingxi.entity.Post;
import com.linnea.lingxi.entity.User;
import com.linnea.lingxi.mapper.CommentMapper;
import com.linnea.lingxi.mapper.PostMapper;
import com.linnea.lingxi.mapper.UserMapper;
import com.linnea.lingxi.service.PostService;
import com.linnea.lingxi.vo.FeedPageVO;
import com.linnea.lingxi.vo.PostInteractionCountVO;
import com.linnea.lingxi.vo.PostPageVO;
import com.linnea.lingxi.vo.PostVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 动态业务实现：发布落库 + 信息流键集分页。
 *
 * <p>分页策略：不使用 LIMIT offset，也不做 COUNT。每页多取一条用于判断是否还有下一页，
 * 游标取本页最后一行的 (is_pinned, create_time, id)，与索引 idx_feed_cursor 对齐。</p>
 *
 * <p>计数策略：点赞数与评论数不做冗余列，信息流按页实时统计（一次 IN + 两个标量子查询），
 * 与「不引入双写与漂移校准」的取舍一致，详见 {@link #fillInteractionCount}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    /** 网页端发布来源标记，取值与 DDL 中 source 列注释一致。 */
    private static final String SOURCE_WEB = "web";

    /** Agent 代发来源标记：只有通过 API Key 认证的调用方才可能产生。 */
    private static final String SOURCE_AGENT = "agent";

    /** 非置顶标记：网页端发布不置顶。 */
    private static final int PINNED_NO = 0;

    /** 置顶标记：与 DDL 中 is_pinned 取值一致，Agent 代发置顶，同时用于把布尔还原成库内数值。 */
    private static final int PINNED_YES = 1;

    /** 信息流默认每页条数。 */
    private static final int DEFAULT_FEED_PAGE_SIZE = 10;

    /** 列表查询每页上限，与 ListQueryDTO 的 @Max 保持一致：服务层再兜一次，防止校验被绕过时打库。 */
    private static final int MAX_LIST_PAGE_SIZE = 30;

    /** 发布动态的幂等场景标识，参与拼 Redis 键。 */
    private static final String IDEMPOTENT_SCENE_PUBLISH = "post:publish";

    /** 动态数据访问。 */
    private final PostMapper postMapper;

    /** 用户数据访问：发布后回读作者昵称与头像。 */
    private final UserMapper userMapper;

    /** 幂等栅栏：同一 requestId 的重复发布只落一条动态。 */
    private final IdempotentGuard idempotentGuard;

    /** 评论数据访问：删除动态时级联逻辑删除其下评论，避免留下无法访问的孤儿评论。 */
    private final CommentMapper commentMapper;

    /**
     * 发布一条动态。
     *
     * @param createPostInfo  发布请求参数
     * @param publisherUserId 当前登录用户ID
     * @param agent           是否为 Agent 身份
     * @return 发布后的动态信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PostVO createPost(CreatePostDTO createPostInfo, Long publisherUserId, boolean agent) {
        // 步骤1：身份兜底校验，正常由认证过滤器保证非空，缺失说明鉴权链路被绕过
        if (publisherUserId == null) {
            log.warn("发布动态被拒绝, 缺少发布者身份");
            throw new BusinessException(ErrorCode.COMMON_UNAUTHORIZED);
        }
        // 步骤2：清洗正文，@NotBlank 拦不住全空白字符串（如多个空格），此处兜底
        String content = createPostInfo.getContent() == null ? null : createPostInfo.getContent().trim();
        if (!StringUtils.hasText(content)) {
            log.warn("发布动态被拒绝, 正文为纯空白, userId={}", publisherUserId);
            throw new BusinessException(ErrorCode.COMMON_PARAM_ERROR, "动态正文不能为空");
        }
        log.info("发布动态开始, userId={}, agent={}, contentLength={}", publisherUserId, agent, content.length());
        // 步骤3：幂等键非空时抢占，抢占失败说明是重复提交，直接返回首次结果
        String requestId = StringUtils.hasText(createPostInfo.getRequestId())
                ? createPostInfo.getRequestId().trim() : null;
        if (requestId != null
                && !idempotentGuard.tryAcquire(IDEMPOTENT_SCENE_PUBLISH, requestId, publisherUserId)) {
            PostVO existedPostInfo = resolveIdempotentResult(requestId, publisherUserId);
            log.info("发布动态命中幂等, postId={}, userId={}", existedPostInfo.getPostId(), publisherUserId);
            return existedPostInfo;
        }
        // 步骤4：业务执行失败要释放幂等键，否则客户端在 TTL 内无法重试
        try {
            PostVO createdPostInfo = doCreatePost(content, publisherUserId, agent);
            // 步骤5：回填幂等结果，重复请求据此返回同一条动态
            if (requestId != null) {
                idempotentGuard.bindResult(IDEMPOTENT_SCENE_PUBLISH, requestId, publisherUserId,
                        String.valueOf(createdPostInfo.getPostId()));
            }
            return createdPostInfo;
        } catch (RuntimeException createException) {
            if (requestId != null) {
                idempotentGuard.release(IDEMPOTENT_SCENE_PUBLISH, requestId, publisherUserId);
            }
            log.error("发布动态失败, userId={}, agent={}", publisherUserId, agent, createException);
            throw createException;
        }
    }

    /**
     * 按键集分页查询全局信息流。
     *
     * @param feedQueryInfo 分页参数
     * @return 当前页动态及下一页游标
     */
    @Override
    public FeedPageVO queryFeed(FeedQueryDTO feedQueryInfo) {
        // 步骤1：归一化每页条数，DTO 已限制 1-30，未传时取默认值
        int pageSize = feedQueryInfo.getSize() == null ? DEFAULT_FEED_PAGE_SIZE : feedQueryInfo.getSize();
        // 步骤2：解码游标，首页无游标；非法游标在解码处直接抛参数错误
        FeedCursor feedCursor = FeedCursor.decode(feedQueryInfo.getCursor());
        log.info("查询信息流开始, pageSize={}, hasCursor={}", pageSize, feedCursor != null);
        // 步骤3：多取一条探测下一页，避免为总数额外执行 COUNT
        List<PostVO> feedRows = postMapper.selectFeedList(
                feedCursor == null ? null : feedCursor.getPinned(),
                feedCursor == null ? null : feedCursor.getCreateTime(),
                feedCursor == null ? null : feedCursor.getPostId(),
                pageSize + 1);
        // 步骤4：裁剪掉探测行，只有确实存在下一页时才生成游标
        boolean hasMore = feedRows.size() > pageSize;
        List<PostVO> pagePostList = hasMore ? new ArrayList<>(feedRows.subList(0, pageSize)) : feedRows;
        String nextCursor = null;
        if (hasMore && !pagePostList.isEmpty()) {
            PostVO lastItem = pagePostList.get(pagePostList.size() - 1);
            nextCursor = FeedCursor.encode(Boolean.TRUE.equals(lastItem.getIsPinned()) ? PINNED_YES : PINNED_NO,
                    lastItem.getCreateTime(), lastItem.getPostId());
        }
        // 步骤5：归一化作者展示字段，保证与发布接口口径一致（注销作者给占位昵称）
        normalizePostList(pagePostList);
        // 步骤6：批量补齐点赞数与评论数，一次 IN 查询避免逐条统计
        fillInteractionCount(pagePostList);
        // 步骤7：组装返回结构
        FeedPageVO feedPage = new FeedPageVO();
        feedPage.setPostList(pagePostList);
        feedPage.setNextCursor(nextCursor);
        feedPage.setHasMore(hasMore);
        log.info("查询信息流完成, itemCount={}, hasMore={}", pagePostList.size(), hasMore);
        return feedPage;
    }

    /**
     * 归一化信息流列表的作者展示字段。
     *
     * <p>信息流走自定义 XML 联表查询，作者注销时联表结果为 NULL；
     * 而发布接口走 {@link PostVO#from} 的兜底逻辑，不归一化会出现同一个作者
     * 「发布时显示占位昵称、列表里显示 null」的口径不一致。</p>
     *
     * @param postList 当前页动态，可为空集合
     */
    private void normalizePostList(List<PostVO> postList) {
        // 步骤1：逐条补齐空值，集合为空时直接返回
        if (postList == null || postList.isEmpty()) {
            return;
        }
        for (PostVO postItem : postList) {
            if (!StringUtils.hasText(postItem.getAuthorNickname())) {
                postItem.setAuthorNickname(PostVO.DELETED_AUTHOR_NICKNAME);
            }
            if (postItem.getAuthorAvatarUrl() == null) {
                postItem.setAuthorAvatarUrl("");
            }
        }
        log.info("信息流作者字段归一化完成, itemCount={}", postList.size());
    }

    /**
     * 批量补齐信息流中每条动态的点赞数与评论数。
     *
     * <p>WHY 不逐条查：一页最多 30 条，逐条统计会产生 N+1；本方法一次 IN 查询取回全部计数后内存拼装。
     * WHY 不加冗余计数列：加列需要同时改点赞、取消点赞、发表评论、删除评论四条写路径并配套回填与漂移校准，
     * 当前数据量下实时统计（两个走 idx_post_id 的标量子查询）成本可接受，维护成本更低；
     * 等单页统计耗时或 post_like 行数成为瓶颈时再切冗余列，届时只需替换本方法。</p>
     *
     * @param pagePostList 当前页动态，可为空集合
     */
    private void fillInteractionCount(List<PostVO> pagePostList) {
        // 步骤1：空页直接返回，同时避免 XML 中 IN () 的语法错误
        if (pagePostList == null || pagePostList.isEmpty()) {
            return;
        }
        // 步骤2：一次 IN 查询取回当前页所有动态的互动计数
        List<Long> postIdList = pagePostList.stream().map(PostVO::getPostId).toList();
        List<PostInteractionCountVO> countRows = postMapper.selectPostCountList(postIdList);
        Map<Long, PostInteractionCountVO> countMap = countRows.stream()
                .collect(Collectors.toMap(PostInteractionCountVO::getPostId, countItem -> countItem));
        // 步骤3：逐个回填，结果缺失时兜底 0，保证列表字段永不为 null
        for (PostVO postItem : pagePostList) {
            PostInteractionCountVO countItem = countMap.get(postItem.getPostId());
            postItem.setLikeCount(countItem == null || countItem.getLikeCount() == null
                    ? 0L : countItem.getLikeCount());
            postItem.setCommentCount(countItem == null || countItem.getCommentCount() == null
                    ? 0L : countItem.getCommentCount());
        }
        log.info("信息流互动计数填充完成, postCount={}, countRows={}", postIdList.size(), countRows.size());
    }

    /**
     * 落库并组装发布结果，拆出来便于幂等逻辑在外层统一处理失败释放。
     *
     * @param content         已清洗的正文
     * @param publisherUserId 发布者ID
     * @param agent           是否为 Agent 身份
     * @return 发布后的动态信息
     */
    private PostVO doCreatePost(String content, Long publisherUserId, boolean agent) {
        // 步骤1：组装实体，来源与置顶由身份决定，不接受前端传入
        Post newPost = new Post();
        newPost.setUserId(publisherUserId);
        newPost.setContent(content);
        newPost.setSource(agent ? SOURCE_AGENT : SOURCE_WEB);
        newPost.setIsPinned(agent ? PINNED_YES : PINNED_NO);
        postMapper.insert(newPost);
        // 步骤2：回读落库结果，create_time 由数据库默认值填充，插入后的实体上仍为 null
        Post createdPost = postMapper.selectById(newPost.getId());
        if (createdPost == null) {
            throw new SystemException("动态插入后回读为空, postId=" + newPost.getId());
        }
        // 步骤3：查询作者资料并组装对外对象
        User publisher = userMapper.selectById(publisherUserId);
        PostVO createdPostInfo = PostVO.from(createdPost, publisher);
        log.info("发布动态落库完成, postId={}, userId={}, source={}",
                createdPostInfo.getPostId(), publisherUserId, createdPost.getSource());
        return createdPostInfo;
    }

    /**
     * 处理幂等命中：返回首次发布的那条动态。
     *
     * <p>幂等键值为占位符表示首个请求仍在处理（并发重复提交），此时不能返回结果，也不能放行去重复落库，
     * 只能让客户端稍后重试或复用首次响应。</p>
     *
     * @param requestId       客户端幂等键
     * @param publisherUserId 发布者ID
     * @return 首次发布的动态信息
     */
    private PostVO resolveIdempotentResult(String requestId, Long publisherUserId) {
        // 步骤1：读取幂等键当前值
        String resultValue = idempotentGuard.queryResult(IDEMPOTENT_SCENE_PUBLISH, requestId, publisherUserId);
        // 步骤2：键已消失或仍在处理中，都按重复提交拒绝，避免出现两条动态
        if (resultValue == null || idempotentGuard.isProcessing(resultValue)) {
            log.warn("发布动态被拒绝, 相同幂等键处理中或结果已过期, userId={}", publisherUserId);
            throw new BusinessException(ErrorCode.COMMON_DUPLICATE_REQUEST);
        }
        // 步骤3：解析首次结果的动态ID并回查，动态已被删除时同样按重复提交拒绝
        Long existedPostId;
        try {
            existedPostId = Long.valueOf(resultValue);
        } catch (NumberFormatException numberFormatException) {
            log.warn("幂等键值非法, 按重复提交拒绝, userId={}", publisherUserId, numberFormatException);
            throw new BusinessException(ErrorCode.COMMON_DUPLICATE_REQUEST);
        }
        Post existedPost = postMapper.selectById(existedPostId);
        if (existedPost == null) {
            log.warn("幂等命中但动态已删除, postId={}, userId={}", existedPostId, publisherUserId);
            throw new BusinessException(ErrorCode.COMMON_DUPLICATE_REQUEST);
        }
        // 步骤4：组装首次结果返回，作者信息重新查询以保证昵称头像为最新
        User publisher = userMapper.selectById(publisherUserId);
        return PostVO.from(existedPost, publisher);
    }

    /**
     * 修改动态正文。
     *
     * @param postId         动态ID
     * @param updatePostInfo 修改请求参数
     * @param operatorUserId 当前登录用户ID
     * @return 修改后的动态信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PostVO updatePost(Long postId, UpdatePostDTO updatePostInfo, Long operatorUserId) {
        // 步骤1：身份兜底，正常由认证过滤器保证非空
        if (operatorUserId == null) {
            log.warn("修改动态被拒绝, 缺少操作者身份, postId={}", postId);
            throw new BusinessException(ErrorCode.COMMON_UNAUTHORIZED);
        }
        // 步骤2：清洗正文，@NotBlank 拦不住多个空格这种纯空白
        String content = updatePostInfo.getContent() == null ? null : updatePostInfo.getContent().trim();
        if (!StringUtils.hasText(content)) {
            log.warn("修改动态被拒绝, 正文为纯空白, postId={}, userId={}", postId, operatorUserId);
            throw new BusinessException(ErrorCode.COMMON_PARAM_ERROR, "动态正文不能为空");
        }
        // 步骤3：回查动态，逻辑删除的查不到，按不存在处理
        Post existedPost = postId == null ? null : postMapper.selectById(postId);
        if (existedPost == null) {
            log.warn("修改动态被拒绝, 动态不存在或已删除, postId={}, userId={}", postId, operatorUserId);
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }
        // 步骤4：仅作者可改；来源与置顶刻意不在可改字段内，防止被改成「Agent 置顶」
        if (!operatorUserId.equals(existedPost.getUserId())) {
            log.warn("修改动态被拒绝, 非作者操作, postId={}, ownerId={}, userId={}",
                    postId, existedPost.getUserId(), operatorUserId);
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN);
        }
        log.info("修改动态开始, postId={}, userId={}, contentLength={}", postId, operatorUserId, content.length());
        // 步骤5：带 user_id 条件的单行更新，把归属校验下沉到 SQL，避免校验与写入之间的竞态
        int updatedRows = postMapper.update(null, new LambdaUpdateWrapper<Post>()
                .eq(Post::getId, postId)
                .eq(Post::getUserId, operatorUserId)
                .set(Post::getContent, content)
                .set(Post::getUpdateTime, LocalDateTime.now()));
        if (updatedRows == 0) {
            log.warn("修改动态失败, 目标行已不存在, postId={}, userId={}", postId, operatorUserId);
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }
        // 步骤6：回读并补齐互动计数，返回字段与信息流保持一致
        Post updatedPost = postMapper.selectById(postId);
        User author = userMapper.selectById(updatedPost.getUserId());
        PostVO updatedPostInfo = PostVO.from(updatedPost, author);
        fillInteractionCount(List.of(updatedPostInfo));
        log.info("修改动态完成, postId={}, userId={}", postId, operatorUserId);
        return updatedPostInfo;
    }

    /**
     * 删除动态（逻辑删除）并级联删除评论。
     *
     * @param postId         动态ID
     * @param operatorUserId 当前登录用户ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePost(Long postId, Long operatorUserId) {
        // 步骤1：作者自删，必须校验归属
        removePostInternal(postId, operatorUserId, false);
    }

    /**
     * 管理员删除任意动态（逻辑删除）并级联删除评论。
     *
     * @param postId         动态ID
     * @param operatorUserId 管理员ID，仅用于日志追溯
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removePostByAdmin(Long postId, Long operatorUserId) {
        // 步骤1：后台删除，跳过归属校验（是否管理员由控制器 @PreAuthorize 前置判定）
        removePostInternal(postId, operatorUserId, true);
    }

    /**
     * 删除动态的统一实现：作者自删与后台删除共用同一套删除范围。
     *
     * <p>WHY 抽成一个方法而不是复制两份：两者的差别只有「要不要校验归属」一句话，
     * 复制出去后一旦删除范围变化（例如将来要清理通知），两份实现必然只改一处。</p>
     *
     * @param postId         动态ID
     * @param operatorUserId 操作者用户ID
     * @param asAdmin        true 表示后台删除，跳过归属校验
     */
    private void removePostInternal(Long postId, Long operatorUserId, boolean asAdmin) {
        // 步骤1：身份兜底
        if (operatorUserId == null) {
            log.warn("删除动态被拒绝, 缺少操作者身份, postId={}", postId);
            throw new BusinessException(ErrorCode.COMMON_UNAUTHORIZED);
        }
        // 步骤2：回查动态，逻辑删除的查不到
        Post existedPost = postId == null ? null : postMapper.selectById(postId);
        if (existedPost == null) {
            log.warn("删除动态被拒绝, 动态不存在或已删除, postId={}, userId={}, asAdmin={}",
                    postId, operatorUserId, asAdmin);
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }
        // 步骤3：非管理员必须校验归属，只有作者能删
        if (!asAdmin && !operatorUserId.equals(existedPost.getUserId())) {
            log.warn("删除动态被拒绝, 非作者操作, postId={}, ownerId={}, userId={}",
                    postId, existedPost.getUserId(), operatorUserId);
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN);
        }
        log.info("删除动态开始, postId={}, userId={}, asAdmin={}, ownerId={}",
                postId, operatorUserId, asAdmin, existedPost.getUserId());
        // 步骤4：逻辑删除动态；deletedRows=0 说明已被并发删除，按不存在处理
        int deletedRows = postMapper.deleteById(postId);
        if (deletedRows == 0) {
            log.warn("删除动态失败, 目标行已不存在, postId={}, userId={}, asAdmin={}",
                    postId, operatorUserId, asAdmin);
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }
        // 步骤5：级联删除评论。点赞行保留不动物理数据，列表查询靠 JOIN post 过滤已删动态
        int deletedCommentRows = commentMapper.softDeleteByPostId(postId);
        log.info("删除动态完成, postId={}, userId={}, asAdmin={}, deletedCommentRows={}",
                postId, operatorUserId, asAdmin, deletedCommentRows);
    }

    /**
     * 分页查询我赞过的动态。
     *
     * @param userId        目标用户ID
     * @param listQueryInfo 分页参数
     * @return 动态分页结果
     */
    @Override
    public PostPageVO queryMyLikedPostPage(Long userId, ListQueryDTO listQueryInfo) {
        // 步骤1：身份兜底，未认证不允许查任何人的点赞记录
        if (userId == null) {
            log.warn("查询我赞过的动态被拒绝, 缺少用户身份");
            throw new BusinessException(ErrorCode.COMMON_UNAUTHORIZED);
        }
        // 步骤2：归一化条数并解码游标
        int pageSize = resolveListPageSize(listQueryInfo);
        TimeIdCursor likedCursor = TimeIdCursor.decode(listQueryInfo.getCursor());
        log.info("查询我赞过的动态开始, userId={}, pageSize={}, hasCursor={}", userId, pageSize, likedCursor != null);
        // 步骤3：多取一条探测下一页；游标列是点赞时间与动态ID，与 SQL 的排序键一致
        List<PostVO> postRows = postMapper.selectLikedPostList(userId,
                likedCursor == null ? null : likedCursor.getCreateTime(),
                likedCursor == null ? null : likedCursor.getId(),
                pageSize + 1);
        boolean hasMore = postRows.size() > pageSize;
        List<PostVO> pagePostList = hasMore ? new ArrayList<>(postRows.subList(0, pageSize)) : postRows;
        // 步骤4：末页不生成游标；有下一页时取本页最后一行的 (点赞时间, 动态ID)
        String nextCursor = null;
        if (hasMore) {
            PostVO lastPostInfo = pagePostList.get(pageSize - 1);
            nextCursor = TimeIdCursor.encode(lastPostInfo.getLikedAt(), lastPostInfo.getPostId());
        }
        // 步骤5：批量补齐互动计数，字段口径与信息流一致
        fillInteractionCount(pagePostList);
        PostPageVO likedPostPage = new PostPageVO();
        likedPostPage.setPostList(pagePostList);
        likedPostPage.setNextCursor(nextCursor);
        likedPostPage.setHasMore(hasMore);
        log.info("查询我赞过的动态完成, userId={}, itemCount={}, hasMore={}",
                userId, pagePostList.size(), hasMore);
        return likedPostPage;
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
                ? DEFAULT_FEED_PAGE_SIZE
                : Math.max(1, Math.min(requestSize, MAX_LIST_PAGE_SIZE));
        // 步骤2：记录最终条数，便于排查「参数已传但按默认值分页」的问题
        log.info("列表分页条数解析完成, pageSize={}", pageSize);
        return pageSize;
    }
}
