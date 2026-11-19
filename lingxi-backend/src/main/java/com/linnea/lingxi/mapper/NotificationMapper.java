package com.linnea.lingxi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linnea.lingxi.entity.Notification;
import com.linnea.lingxi.vo.NotificationVO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息通知数据访问入口。
 *
 * <p>插入走 {@link BaseMapper}；列表（联表取触发者资料 + 键集游标）、未读数、标记已读
 * 都在 NotificationMapper.xml 中实现，保证 SQL 集中可审。</p>
 */
public interface NotificationMapper extends BaseMapper<Notification> {

    /**
     * 分页查询某人的通知列表，按通知时间倒序。
     *
     * @param userId           接收者用户ID
     * @param unreadOnly       是否只查未读
     * @param cursorCreateTime 游标中的通知时间，首页传 null
     * @param cursorId         游标中的通知ID，首页传 null
     * @param limit            每页条数（已由业务层 +1 探测 hasMore）
     * @return 通知列表（含触发者昵称与头像）
     */
    List<NotificationVO> selectNotificationList(@Param("userId") Long userId,
                                                @Param("unreadOnly") boolean unreadOnly,
                                                @Param("cursorCreateTime") LocalDateTime cursorCreateTime,
                                                @Param("cursorId") Long cursorId,
                                                @Param("limit") int limit);

    /**
     * 统计未读通知数。
     *
     * @param userId 接收者用户ID
     * @return 未读条数，无数据时为 0
     */
    long countUnread(@Param("userId") Long userId);

    /**
     * 把单条通知标记为已读。
     *
     * <p>WHY 必须带 user_id 条件：只按主键更新会让任何登录用户都能把别人的通知改成已读（越权写）。</p>
     *
     * @param notificationId 通知ID
     * @param userId         接收者用户ID
     * @return 受影响行数：1-本次标记成功，0-通知不存在/不属于本人/原本已读
     */
    int markRead(@Param("notificationId") Long notificationId, @Param("userId") Long userId);

    /**
     * 把某人的全部未读通知标记为已读。
     *
     * @param userId 接收者用户ID
     * @return 受影响行数，即本次从未读变为已读的条数
     */
    int markAllRead(@Param("userId") Long userId);
}
