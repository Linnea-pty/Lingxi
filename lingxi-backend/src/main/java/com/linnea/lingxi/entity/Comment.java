package com.linnea.lingxi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评论表实体，对应 lingxi.comment。
 *
 * <p>评论以两级展示、多级存储：parent_id 记录直接父评论（0 表示一级评论），
 * root_id 记录所属讨论串根评论（一级评论的 root_id 等于自身 id，回复继承父评论的 root_id），
 * 该字段必须与插入在同一事务内回填，否则讨论串查询会丢节点。</p>
 */
@Data
@TableName("comment")
public class Comment {

    /** 主键ID，数据库自增。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属动态ID，关联 post.id。 */
    private Long postId;

    /** 评论人用户ID，关联 user.id。 */
    private Long userId;

    /** 父评论ID：0-一级评论，否则为直接父评论ID，用于前端还原层级。 */
    private Long parentId;

    /** 所属讨论串根评论ID：一级评论等于自身 id，回复继承父评论的 root_id。 */
    private Long rootId;

    /** 被回复人用户ID：0-无（一级评论或未指定）。 */
    private Long replyUserId;

    /** 评论正文，TEXT 类型。 */
    private String content;

    /** 逻辑删除标记：0-未删除 1-已删除，由 MyBatis-Plus 自动维护。 */
    @TableLogic
    private Integer isDeleted;

    /** 创建时间（业务本地时区），由数据库默认值填入。 */
    private LocalDateTime createTime;

    /** 更新时间（业务本地时区），由数据库 ON UPDATE 维护。 */
    private LocalDateTime updateTime;
}
