package com.linnea.lingxi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 动态点赞表实体，对应 lingxi.post_like。
 *
 * <p>本表不使用逻辑删除注解：取消点赞通过 status 置 0 且 deleted_id 置为非 0 值，
 * 依靠唯一索引 uk_user_post_deleted(user_id, post_id, deleted_id) 保证同一用户对同一动态
 * 只有一行有效点赞记录。全局 logic-delete-field=isDeleted 对本表无影响（无该属性）。</p>
 */
@Data
@TableName("post_like")
public class PostLike {

    /** 主键ID，数据库自增；取消点赞时用于回填 deleted_id 以释放活跃唯一位。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 点赞用户ID，关联 user.id。 */
    private Long userId;

    /** 被点赞动态ID，关联 post.id。 */
    private Long postId;

    /** 点赞状态：1-有效点赞 0-已取消，业务查询条件。 */
    private Integer status;

    /** 软删除标记：未删除固定为 0，取消点赞时置为本行 id 或任意非 0 值。 */
    private Long deletedId;

    /** 创建时间（业务本地时区），由数据库默认值填入。 */
    private LocalDateTime createTime;

    /** 更新时间（业务本地时区），由数据库 ON UPDATE 维护。 */
    private LocalDateTime updateTime;
}
