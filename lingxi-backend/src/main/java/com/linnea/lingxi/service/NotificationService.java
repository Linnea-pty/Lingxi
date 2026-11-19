package com.linnea.lingxi.service;

import com.linnea.lingxi.dto.NotificationQueryDTO;
import com.linnea.lingxi.vo.NotificationPageVO;
import com.linnea.lingxi.vo.UnreadCountVO;

/**
 * 消息通知业务接口。
 *
 * <p>分两类方法：
 * ① {@code notifyXxx}——由点赞/评论/关注等业务在同一事务内调用，投递一条通知；
 * ② 查询与已读——只操作当前登录用户自己的通知，越权在 SQL 的 WHERE 里拦住。</p>
 *
 * <p>投递类方法不抛业务异常：通知是附属产物，缺参数或自我触发时静默跳过并记日志，
 * 不能因为「发不出通知」把已经成功的点赞/评论/关注一起回滚。</p>
 */
public interface NotificationService {

    /**
     * 投递「赞了你的动态」通知。
     *
     * @param postId       被点赞的动态ID
     * @param postAuthorId 动态作者（接收者）
     * @param actorId      点赞人（触发者）
     */
    void notifyPostLiked(Long postId, Long postAuthorId, Long actorId);

    /**
     * 投递「评论了你的动态」通知。
     *
     * @param postId       被评论的动态ID
     * @param postAuthorId 动态作者（接收者）
     * @param commentId    新评论ID
     * @param actorId      评论人（触发者）
     */
    void notifyPostCommented(Long postId, Long postAuthorId, Long commentId, Long actorId);

    /**
     * 投递「回复了你的评论」通知。
     *
     * @param postId         所属动态ID
     * @param repliedUserId  被回复的评论作者（接收者）
     * @param replyCommentId 回复评论ID
     * @param actorId        回复人（触发者）
     */
    void notifyCommentReplied(Long postId, Long repliedUserId, Long replyCommentId, Long actorId);

    /**
     * 投递「关注了你」通知。
     *
     * @param followedUserId 被关注的人（接收者）
     * @param actorId        发起关注的人（触发者）
     */
    void notifyFollowed(Long followedUserId, Long actorId);

    /**
     * 分页查询当前用户的通知列表。
     *
     * @param userId    当前登录用户ID
     * @param queryInfo 查询参数（游标、条数、是否只看未读）
     * @return 通知分页结果，含最新未读数
     */
    NotificationPageVO queryNotificationPage(Long userId, NotificationQueryDTO queryInfo);

    /**
     * 查询当前用户的未读通知数。
     *
     * @param userId 当前登录用户ID
     * @return 未读数（为 0 时也返回对象，不回 null）
     */
    UnreadCountVO queryUnreadCount(Long userId);

    /**
     * 把单条通知标记为已读。
     *
     * <p>幂等：通知不存在、不属于本人、已经读过都按成功处理（返回最新未读数），
     * 不区分这三种情况，避免通过错误码探测别人的通知ID是否存在。</p>
     *
     * @param notificationId 通知ID
     * @param userId         当前登录用户ID
     * @return 标记后的最新未读数
     */
    UnreadCountVO markRead(Long notificationId, Long userId);

    /**
     * 把当前用户全部未读通知标记为已读。
     *
     * @param userId 当前登录用户ID
     * @return 标记后的最新未读数（正常为 0）
     */
    UnreadCountVO markAllRead(Long userId);
}
