package com.linnea.lingxi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 动态表实体，对应 lingxi.post。
 *
 * <p>id 同时充当信息流游标排序字段，配合索引 idx_feed_cursor(is_pinned, create_time, id) 做键集分页；
 * 禁止使用 LIMIT offset 深分页。</p>
 *
 * <p>逻辑删除列 is_deleted 由全局配置 logic-delete-field=isDeleted 接管。</p>
 */
@Data
@TableName("post")
public class Post {

    /** 主键ID，数据库自增，同时作为信息流游标排序字段。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发布者用户ID，关联 user.id。 */
    private Long userId;

    /** 动态正文，TEXT 类型。 */
    private String content;

    /** 发布来源：web-网页端发布，agent-AI 助手代发；取值受 DDL 注释约束，当前以常量字符串维护。 */
    private String source;

    /** 是否置顶：0-否 1-是，Agent 代发动态置 1。 */
    private Integer isPinned;

    /** 逻辑删除标记：0-未删除 1-已删除，由 MyBatis-Plus 自动维护。 */
    @TableLogic
    private Integer isDeleted;

    /** 创建时间（业务本地时区），由数据库默认值填入。 */
    private LocalDateTime createTime;

    /** 更新时间（业务本地时区），由数据库 ON UPDATE 维护。 */
    private LocalDateTime updateTime;
}
