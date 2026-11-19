package com.linnea.lingxi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户关注关系实体，对应 lingxi.user_follow。
 *
 * <p>刻意不带 is_deleted：关注/取关用 status 表达，一行就是一条关系。
 * 若再加逻辑删除列，取关后的旧行会继续占用唯一键 uk_user_follow，
 * 导致「取关后再关注」插入失败。</p>
 */
@Data
@TableName("user_follow")
public class UserFollow {

    /** 主键ID，数据库自增。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关注发起方用户ID，关联 user.id。 */
    private Long userId;

    /** 被关注方用户ID，关联 user.id。 */
    private Long followUserId;

    /** 关注状态：1-已关注 0-已取关。 */
    private Integer status;

    /** 最近一次关注时间：重新关注会刷新，作为关注/粉丝列表的排序与游标键。 */
    private LocalDateTime followTime;

    /** 创建时间（业务本地时区）。 */
    private LocalDateTime createTime;

    /** 更新时间（业务本地时区）。 */
    private LocalDateTime updateTime;
}
