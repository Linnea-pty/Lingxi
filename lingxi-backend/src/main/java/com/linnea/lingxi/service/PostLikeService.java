package com.linnea.lingxi.service;

import com.linnea.lingxi.dto.ListQueryDTO;
import com.linnea.lingxi.vo.LikeResultVO;
import com.linnea.lingxi.vo.LikeUserPageVO;

/**
 * 点赞业务服务：点赞与取消点赞，两个操作都保证幂等。
 *
 * <p>同一用户对同一动态只允许一条有效点赞记录，并发由唯一索引
 * uk_user_post_deleted(user_id, post_id, deleted_id) 兜底，不依赖「先查后写」。</p>
 */
public interface PostLikeService {

    /**
     * 点赞；已点赞时按幂等成功处理，不报错也不重复计数。
     *
     * @param postId 被点赞动态ID
     * @param userId 点赞用户ID，来自认证上下文
     * @return 操作后的点赞状态与总数
     */
    LikeResultVO likePost(Long postId, Long userId);

    /**
     * 取消点赞；未点赞时按幂等成功处理。
     *
     * <p>取消是带 WHERE 的单行 UPDATE（status=0、deleted_id=本行ID），不做物理删除，
     * 保留行为审计并释放活跃唯一位。</p>
     *
     * @param postId 被取消动态ID
     * @param userId 取消用户ID，来自认证上下文
     * @return 操作后的点赞状态与总数
     */
    LikeResultVO cancelLike(Long postId, Long userId);

    /**
     * 分页查询某条动态的点赞人列表，按点赞时间倒序。
     *
     * @param postId        动态ID
     * @param listQueryInfo 分页参数
     * @return 点赞人分页结果
     */
    LikeUserPageVO queryLikeUserPage(Long postId, ListQueryDTO listQueryInfo);
}
