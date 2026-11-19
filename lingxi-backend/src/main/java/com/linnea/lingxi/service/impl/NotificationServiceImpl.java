package com.linnea.lingxi.service.impl;

import com.linnea.lingxi.common.cursor.TimeIdCursor;
import com.linnea.lingxi.common.enums.NotificationType;
import com.linnea.lingxi.common.exception.BusinessException;
import com.linnea.lingxi.common.exception.ErrorCode;
import com.linnea.lingxi.dto.NotificationQueryDTO;
import com.linnea.lingxi.entity.Notification;
import com.linnea.lingxi.mapper.NotificationMapper;
import com.linnea.lingxi.service.NotificationService;
import com.linnea.lingxi.vo.NotificationPageVO;
import com.linnea.lingxi.vo.NotificationVO;
import com.linnea.lingxi.vo.UnreadCountVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息通知业务实现。
 *
 * <p>三条一致性约定：
 * ① 投递与业务写入同事务：点赞成功必有对应通知，点赞回滚通知也一起消失，不会出现「有通知没点赞」；
 * ② 自我触达（自己赞/评自己的动态）不入库，库里不留永远不会被读的行；
 * ③ 通知表只追加，已读只翻标记位；越权由 SQL 的 {@code AND user_id = ?} 拦住，不靠业务层判断。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    /** 列表默认每页条数，与 ListQueryDTO 的默认值保持一致。 */
    private static final int DEFAULT_PAGE_SIZE = 10;

    /** 列表每页条数上限，与 ListQueryDTO 的 @Max(30) 保持一致。 */
    private static final int MAX_PAGE_SIZE = 30;

    /** 与动态无关的通知（关注）统一写 0，避免库里出现 null 让前端判空分支变多。 */
    private static final long NO_POST_ID = 0L;

    /** 未读标记：0-未读。 */
    private static final int UNREAD = 0;

    /** 通知数据访问。 */
    private final NotificationMapper notificationMapper;

    /**
     * 投递「赞了你的动态」通知。
     *
     * @param postId       被点赞的动态ID
     * @param postAuthorId 动态作者（接收者）
     * @param actorId      点赞人（触发者）
     */
    @Override
    public void notifyPostLiked(Long postId, Long postAuthorId, Long actorId) {
        // 步骤1：记录投递意图，便于和点赞日志按 postId 串联
        log.info("准备投递点赞通知, postId={}, receiverId={}, actorId={}", postId, postAuthorId, actorId);
        // 步骤2：目标ID 与动态ID 同值（点赞的对象就是动态本身）
        sendNotification(postAuthorId, actorId, NotificationType.LIKE_POST, postId, postId);
    }

    /**
     * 投递「评论了你的动态」通知。
     *
     * @param postId       被评论的动态ID
     * @param postAuthorId 动态作者（接收者）
     * @param commentId    新评论ID
     * @param actorId      评论人（触发者）
     */
    @Override
    public void notifyPostCommented(Long postId, Long postAuthorId, Long commentId, Long actorId) {
        // 步骤1：记录投递意图
        log.info("准备投递评论通知, postId={}, commentId={}, receiverId={}, actorId={}",
                postId, commentId, postAuthorId, actorId);
        // 步骤2：目标ID 是评论ID，前端可直接定位到那条评论
        sendNotification(postAuthorId, actorId, NotificationType.COMMENT_POST, commentId, postId);
    }

    /**
     * 投递「回复了你的评论」通知。
     *
     * @param postId         所属动态ID
     * @param repliedUserId  被回复的评论作者（接收者）
     * @param replyCommentId 回复评论ID
     * @param actorId        回复人（触发者）
     */
    @Override
    public void notifyCommentReplied(Long postId, Long repliedUserId, Long replyCommentId, Long actorId) {
        // 步骤1：记录投递意图
        log.info("准备投递回复通知, postId={}, replyCommentId={}, receiverId={}, actorId={}",
                postId, replyCommentId, repliedUserId, actorId);
        // 步骤2：目标ID 是回复评论ID
        sendNotification(repliedUserId, actorId, NotificationType.REPLY_COMMENT, replyCommentId, postId);
    }

    /**
     * 投递「关注了你」通知。
     *
     * @param followedUserId 被关注的人（接收者）
     * @param actorId        发起关注的人（触发者）
     */
    @Override
    public void notifyFollowed(Long followedUserId, Long actorId) {
        // 步骤1：记录投递意图
        log.info("准备投递关注通知, receiverId={}, actorId={}", followedUserId, actorId);
        // 步骤2：关注类通知没有动态，目标ID 用触发者ID，点击直接跳对方主页
        sendNotification(followedUserId, actorId, NotificationType.FOLLOW, actorId, NO_POST_ID);
    }

    /**
     * 分页查询当前用户的通知列表。
     *
     * @param userId    当前登录用户ID
     * @param queryInfo 查询参数（游标、条数、是否只看未读）
     * @return 通知分页结果，含最新未读数
     */
    @Override
    public NotificationPageVO queryNotificationPage(Long userId, NotificationQueryDTO queryInfo) {
        // 步骤1：解析条数与游标
        int pageSize = resolvePageSize(queryInfo);
        TimeIdCursor cursor = TimeIdCursor.decode(queryInfo.getCursor());
        boolean unreadOnly = Boolean.TRUE.equals(queryInfo.getUnreadOnly());
        // 步骤2：多查一条探测 hasMore，省掉一次 COUNT
        List<NotificationVO> queriedList = notificationMapper.selectNotificationList(userId, unreadOnly,
                cursor == null ? null : cursor.getCreateTime(),
                cursor == null ? null : cursor.getId(),
                pageSize + 1);
        // 步骤3：裁剪成页并生成下一页游标（游标键与 ORDER BY 同为「时间 + ID」）
        boolean hasMore = queriedList.size() > pageSize;
        List<NotificationVO> pageList = hasMore ? new ArrayList<>(queriedList.subList(0, pageSize)) : queriedList;
        String nextCursor = null;
        if (hasMore && !pageList.isEmpty()) {
            NotificationVO lastNotification = pageList.get(pageList.size() - 1);
            nextCursor = TimeIdCursor.encode(lastNotification.getCreateTime(), lastNotification.getNotificationId());
        }
        // 步骤4：补中文描述，前端不必维护类型映射表
        pageList.forEach(notificationInfo ->
                notificationInfo.setTypeDescription(resolveTypeDescription(notificationInfo.getType())));
        // 步骤5：一并回传未读数，前端刷新一次拿到「列表 + 红点」
        long unreadCount = notificationMapper.countUnread(userId);
        NotificationPageVO notificationPage = new NotificationPageVO();
        notificationPage.setNotificationList(pageList);
        notificationPage.setNextCursor(nextCursor);
        notificationPage.setHasMore(hasMore);
        notificationPage.setUnreadCount(unreadCount);
        log.info("查询通知列表完成, userId={}, unreadOnly={}, itemCount={}, hasMore={}, unreadCount={}",
                userId, unreadOnly, pageList.size(), hasMore, unreadCount);
        return notificationPage;
    }

    /**
     * 查询当前用户的未读通知数。
     *
     * @param userId 当前登录用户ID
     * @return 未读数
     */
    @Override
    public UnreadCountVO queryUnreadCount(Long userId) {
        // 步骤1：COUNT 走 idx_user_read_time 前两列，不回表
        long unreadCount = notificationMapper.countUnread(userId);
        log.info("查询未读通知数完成, userId={}, unreadCount={}", userId, unreadCount);
        return buildUnreadCount(unreadCount);
    }

    /**
     * 把单条通知标记为已读。
     *
     * @param notificationId 通知ID
     * @param userId         当前登录用户ID
     * @return 标记后的最新未读数
     */
    @Override
    public UnreadCountVO markRead(Long notificationId, Long userId) {
        // 步骤1：ID 缺失按参数错误处理；不查「是不是自己的」，直接交给 SQL 的 WHERE 带 user_id 判断
        if (notificationId == null || notificationId <= 0) {
            log.warn("标记通知已读被拒绝, 通知ID非法, notificationId={}, userId={}", notificationId, userId);
            throw new BusinessException(ErrorCode.COMMON_PARAM_ERROR, "通知ID不合法");
        }
        // 步骤2：影响 0 行（不存在/不属于本人/已读过）也按成功处理，避免用错误码探测他人通知
        int updatedRows = notificationMapper.markRead(notificationId, userId);
        // 步骤3：返回最新未读数，前端直接覆盖角标
        long unreadCount = notificationMapper.countUnread(userId);
        log.info("标记单条通知已读完成, userId={}, notificationId={}, updatedRows={}, unreadCount={}",
                userId, notificationId, updatedRows, unreadCount);
        return buildUnreadCount(unreadCount);
    }

    /**
     * 把当前用户全部未读通知标记为已读。
     *
     * @param userId 当前登录用户ID
     * @return 标记后的最新未读数
     */
    @Override
    public UnreadCountVO markAllRead(Long userId) {
        // 步骤1：批量翻转标记位，只动未读行
        int updatedRows = notificationMapper.markAllRead(userId);
        // 步骤2：回读未读数（正常情况下为 0，回读可兜住并发插入的新通知）
        long unreadCount = notificationMapper.countUnread(userId);
        log.info("标记全部通知已读完成, userId={}, updatedRows={}, unreadCount={}",
                userId, updatedRows, unreadCount);
        return buildUnreadCount(unreadCount);
    }

    /**
     * 统一的投递实现：过滤自我触达后落库一行通知。
     *
     * @param receiverId 接收者用户ID
     * @param actorId    触发者用户ID
     * @param type       通知类型
     * @param targetId   业务目标ID
     * @param postId     关联动态ID，无关时传 0
     */
    private void sendNotification(Long receiverId, Long actorId, NotificationType type, Long targetId, Long postId) {
        // 步骤1：接收者/触发者缺失或为同一人时丢弃——自己操作自己不该收到通知
        if (receiverId == null || actorId == null || targetId == null || receiverId.equals(actorId)) {
            log.info("通知未生成, 接收者或触发者不满足条件, type={}, receiverId={}, actorId={}, targetId={}",
                    type.getCode(), receiverId, actorId, targetId);
            return;
        }
        // 步骤2：组装并插入，create_time 由数据库默认值填充
        Notification notification = new Notification();
        notification.setUserId(receiverId);
        notification.setActorId(actorId);
        notification.setType(type.getCode());
        notification.setTargetId(targetId);
        notification.setPostId(postId == null ? NO_POST_ID : postId);
        notification.setIsRead(UNREAD);
        int insertedRows = notificationMapper.insert(notification);
        log.info("通知已投递, notificationId={}, type={}, receiverId={}, actorId={}, targetId={}, insertedRows={}",
                notification.getId(), type.getCode(), receiverId, actorId, targetId, insertedRows);
    }

    /**
     * 组装未读数出参。
     *
     * @param unreadCount 未读数
     * @return 未读数出参
     */
    private UnreadCountVO buildUnreadCount(long unreadCount) {
        // 步骤1：包装成对象返回，保持「出参永远是对象」的契约
        UnreadCountVO unreadCountInfo = new UnreadCountVO();
        unreadCountInfo.setUnreadCount(unreadCount);
        log.info("未读数出参组装完成, unreadCount={}", unreadCount);
        return unreadCountInfo;
    }

    /**
     * 把通知类型 code 翻译成中文描述。
     *
     * @param typeCode 通知类型 code
     * @return 中文描述；未知 code 原样返回，避免因新增类型未发版导致展示空白
     */
    private String resolveTypeDescription(String typeCode) {
        // 步骤1：线性遍历枚举（只有 4 个值，比建 Map 更直观）
        for (NotificationType type : NotificationType.values()) {
            if (type.getCode().equals(typeCode)) {
                return type.getDescription();
            }
        }
        // 步骤2：未知类型降级为原始 code 并记警告，便于发现「库里出现新类型但枚举没跟」
        log.warn("未知的通知类型, 降级返回原始code, typeCode={}", typeCode);
        return typeCode;
    }

    /**
     * 解析每页条数：未传走默认，越界按上下限兜底。
     *
     * @param queryInfo 查询参数
     * @return 实际使用的每页条数
     */
    private int resolvePageSize(NotificationQueryDTO queryInfo) {
        // 步骤1：未传走默认，传了则上下界兜底（Bean Validation 正常已拦住越界请求）
        Integer requestSize = queryInfo.getSize();
        int pageSize = requestSize == null
                ? DEFAULT_PAGE_SIZE
                : Math.max(1, Math.min(requestSize, MAX_PAGE_SIZE));
        // 步骤2：记录最终条数，便于排查「参数已传但按默认值分页」
        log.info("通知列表分页条数解析完成, pageSize={}", pageSize);
        return pageSize;
    }
}
