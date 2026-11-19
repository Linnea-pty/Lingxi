package com.linnea.lingxi.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linnea.lingxi.common.cursor.TimeIdCursor;
import com.linnea.lingxi.common.exception.BusinessException;
import com.linnea.lingxi.common.exception.ErrorCode;
import com.linnea.lingxi.dto.ListQueryDTO;
import com.linnea.lingxi.entity.Post;
import com.linnea.lingxi.entity.PostLike;
import com.linnea.lingxi.mapper.PostLikeMapper;
import com.linnea.lingxi.mapper.PostMapper;
import com.linnea.lingxi.service.NotificationService;
import com.linnea.lingxi.service.PostLikeService;
import com.linnea.lingxi.vo.LikeResultVO;
import com.linnea.lingxi.vo.LikeUserPageVO;
import com.linnea.lingxi.vo.LikeUserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 点赞业务实现：用唯一索引做并发兜底，不做「先查后写」。
 *
 * <p>为什么不用「先 count 判断是否已点赞再 insert」：两个并发请求会同时通过判断，
 * 第二个必然撞唯一索引；与其多一次查询再把冲突当异常处理，不如让冲突成为正常分支——
 * 插入成功即首次点赞，抛 DuplicateKeyException 即已点赞，两种情况都返回「已点赞」。</p>
 *
 * <p>计数不做冗余列：当前阶段点赞量小，实时 COUNT 用 idx_post_id 定位后再按 status/deleted_id 过滤即可；
 * 后续若成为热点，再引入 post.like_count 冗余列与异步回写。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostLikeServiceImpl implements PostLikeService {

    /** 有效点赞状态值，与 DDL 中 status 列注释一致。 */
    private static final int STATUS_LIKED = 1;

    /** 活跃行的 deleted_id 固定值：0 表示未被取消，参与唯一索引。 */
    private static final long DELETED_ID_ACTIVE = 0L;

    /** 点赞人列表默认每页条数。 */
    private static final int DEFAULT_LIST_PAGE_SIZE = 10;

    /** 列表查询每页上限，与 ListQueryDTO 的 @Max 保持一致：服务层再兜一次，防止校验被绕过时打库。 */
    private static final int MAX_LIST_PAGE_SIZE = 30;

    /** 点赞数据访问。 */
    private final PostLikeMapper postLikeMapper;

    /** 动态数据访问：点赞前校验动态仍然存在。 */
    private final PostMapper postMapper;

    /** 通知业务服务：首次点赞成功后投递「赞了你的动态」。 */
    private final NotificationService notificationService;

    /**
     * 点赞。
     *
     * @param postId 被点赞动态ID
     * @param userId 点赞用户ID
     * @return 操作后的点赞状态与总数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public LikeResultVO likePost(Long postId, Long userId) {
        // 步骤1：校验动态存在，逻辑删除的动态不能再被点赞；同时取回作者ID，用于点赞成功后发通知
        Post existedPost = ensurePostExists(postId, userId);
        // 步骤2：组装活跃点赞行，deleted_id=0 占用唯一索引的「未取消」位置
        PostLike newLike = new PostLike();
        newLike.setUserId(userId);
        newLike.setPostId(postId);
        newLike.setStatus(STATUS_LIKED);
        newLike.setDeletedId(DELETED_ID_ACTIVE);
        // 步骤3：直接插入，撞唯一键说明已点赞，按幂等成功处理
        boolean firstLike = false;
        try {
            postLikeMapper.insert(newLike);
            firstLike = true;
            log.info("点赞落库成功, postId={}, userId={}, likeId={}", postId, userId, newLike.getId());
        } catch (DuplicateKeyException duplicateKeyException) {
            log.info("重复点赞, 按幂等成功处理, postId={}, userId={}", postId, userId);
        }
        // 步骤3.1：通知只投给「首次点赞」，重复点赞不重复打扰作者。
        // WHY 放在 try 之外：若通知插入自身抛异常，写在 try 里会被下面的 DuplicateKey 分支误当成「重复点赞」吞掉。
        if (firstLike) {
            notificationService.notifyPostLiked(postId, existedPost.getUserId(), userId);
        }
        // 步骤4：统计有效点赞总数，返回最新状态
        long likeCount = countActiveLikes(postId);
        log.info("点赞完成, postId={}, userId={}, likeCount={}", postId, userId, likeCount);
        return LikeResultVO.of(true, likeCount);
    }

    /**
     * 取消点赞。
     *
     * @param postId 被取消动态ID
     * @param userId 取消用户ID
     * @return 操作后的点赞状态与总数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public LikeResultVO cancelLike(Long postId, Long userId) {
        // 步骤1：校验动态存在，避免对已删除动态做无意义的写操作
        ensurePostExists(postId, userId);
        // 步骤2：单行 UPDATE 释放活跃唯一位，影响 0 行说明本来就没点赞，同样按幂等成功处理
        int updatedRows = postLikeMapper.cancelLike(userId, postId);
        if (updatedRows == 0) {
            log.info("取消点赞时无有效点赞记录, 按幂等成功处理, postId={}, userId={}", postId, userId);
        } else {
            log.info("取消点赞成功, postId={}, userId={}, updatedRows={}", postId, userId, updatedRows);
        }
        // 步骤3：统计剩余有效点赞总数
        long likeCount = countActiveLikes(postId);
        log.info("取消点赞完成, postId={}, userId={}, likeCount={}", postId, userId, likeCount);
        return LikeResultVO.of(false, likeCount);
    }

    /**
     * 校验动态存在且未被删除。
     *
     * @param postId 动态ID
     * @param userId 当前用户ID，仅用于日志
     * @return 存在的动态实体（调用方需要作者ID发通知，避免二次查库）
     */
    private Post ensurePostExists(Long postId, Long userId) {
        // 步骤1：按主键回查，逻辑删除由 MyBatis-Plus 自动过滤
        Post existedPost = postId == null ? null : postMapper.selectById(postId);
        if (existedPost == null) {
            log.warn("点赞操作被拒绝, 动态不存在或已删除, postId={}, userId={}", postId, userId);
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }
        // 步骤2：把动态返回给调用方复用，省掉「为了拿作者ID再查一次」
        return existedPost;
    }

    /**
     * 统计某个动态的有效点赞总数。
     *
     * @param postId 动态ID
     * @return 有效点赞数，无数据时为 0
     */
    private long countActiveLikes(Long postId) {
        // 步骤1：按 post_id + status + deleted_id 精确统计，idx_post_id 只定位不清表，仍需回表过滤
        Long likeCount = postLikeMapper.selectCount(new LambdaQueryWrapper<PostLike>()
                .eq(PostLike::getPostId, postId)
                .eq(PostLike::getStatus, STATUS_LIKED)
                .eq(PostLike::getDeletedId, DELETED_ID_ACTIVE));
        long activeLikeCount = likeCount == null ? 0L : likeCount;
        // 步骤2：记录统计结果，便于排查「重复点赞被计数」这类唯一键相关异常
        log.info("点赞数统计完成, postId={}, likeCount={}", postId, activeLikeCount);
        return activeLikeCount;
    }

    /**
     * 分页查询某条动态的点赞人列表。
     *
     * @param postId        动态ID
     * @param listQueryInfo 分页参数
     * @return 点赞人分页结果
     */
    @Override
    public LikeUserPageVO queryLikeUserPage(Long postId, ListQueryDTO listQueryInfo) {
        // 步骤1：动态必须存在，已删动态的点赞人列表没有展示意义
        ensurePostExists(postId, null);
        // 步骤2：归一化条数并解码游标
        int pageSize = resolveListPageSize(listQueryInfo);
        TimeIdCursor likeCursor = TimeIdCursor.decode(listQueryInfo.getCursor());
        log.info("查询点赞人列表开始, postId={}, pageSize={}, hasCursor={}", postId, pageSize, likeCursor != null);
        // 步骤3：多取一条探测下一页；游标列 (点赞时间, 点赞人ID) 与 SQL 的 ORDER BY 一致
        List<LikeUserVO> userRows = postLikeMapper.selectLikeUserList(postId,
                likeCursor == null ? null : likeCursor.getCreateTime(),
                likeCursor == null ? null : likeCursor.getId(),
                pageSize + 1);
        boolean hasMore = userRows.size() > pageSize;
        List<LikeUserVO> pageUserList = hasMore ? new ArrayList<>(userRows.subList(0, pageSize)) : userRows;
        // 步骤4：末页不生成游标；有下一页时取本页最后一行的 (点赞时间, 点赞人ID)
        String nextCursor = null;
        if (hasMore) {
            LikeUserVO lastUserInfo = pageUserList.get(pageSize - 1);
            nextCursor = TimeIdCursor.encode(lastUserInfo.getLikedAt(), lastUserInfo.getUserId());
        }
        LikeUserPageVO likeUserPage = new LikeUserPageVO();
        likeUserPage.setUserList(pageUserList);
        likeUserPage.setNextCursor(nextCursor);
        likeUserPage.setHasMore(hasMore);
        log.info("查询点赞人列表完成, postId={}, itemCount={}, hasMore={}", postId, pageUserList.size(), hasMore);
        return likeUserPage;
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
                ? DEFAULT_LIST_PAGE_SIZE
                : Math.max(1, Math.min(requestSize, MAX_LIST_PAGE_SIZE));
        // 步骤2：记录最终条数，便于排查「参数已传但按默认值分页」的问题
        log.info("列表分页条数解析完成, pageSize={}", pageSize);
        return pageSize;
    }
}
