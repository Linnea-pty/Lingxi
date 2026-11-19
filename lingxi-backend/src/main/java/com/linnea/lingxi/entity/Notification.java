package com.linnea.lingxi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息通知实体，对应表 notification。
 *
 * <p>追加型数据：一条事件一行，不合并、不物理删除；已读只翻标记位，不删行。
 * 表结构里没有 is_deleted，因此不参与 MyBatis-Plus 的全局逻辑删除。</p>
 */
@Data
@TableName("notification")
public class Notification {

    /** 主键ID，同时作为键集分页游标的第二排序键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接收者用户ID：这条通知是发给谁的。 */
    private Long userId;

    /** 触发者用户ID：谁点的赞/谁评的论/谁关注了你。 */
    private Long actorId;

    /** 通知类型，取值见 {@link com.linnea.lingxi.common.enums.NotificationType}。 */
    private String type;

    /** 业务目标ID：点赞/评论类为目标评论或动态ID，关注类为关注发起方用户ID。 */
    private Long targetId;

    /** 关联动态ID，仅供前端跳转；0 表示与动态无关（如关注通知）。 */
    private Long postId;

    /** 已读标记：0-未读 1-已读。 */
    private Integer isRead;

    /** 创建时间，库默认值填充，兼作前端展示的通知时间。 */
    private LocalDateTime createTime;

    /** 更新时间，标记已读时由数据库自动刷新。 */
    private LocalDateTime updateTime;
}
