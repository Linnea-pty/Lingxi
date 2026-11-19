package com.linnea.lingxi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 关注/粉丝列表项（View Object）。
 *
 * <p>只暴露展示所需字段：注销账号不会出现在列表里（SQL 已过滤），
 * 因此这里不需要「已注销用户」的兜底文案。</p>
 */
@Data
@Schema(description = "关注/粉丝列表项")
public class FollowUserVO {

    /**
     * 关注关系记录ID。
     *
     * <p>仅供服务端生成下一页游标（游标键是「关注时间 + 关系ID」），前端无需读取或展示。</p>
     */
    @Schema(description = "关注关系记录ID，仅供分页游标使用", example = "35")
    private Long followId;

    /** 对方用户ID。 */
    @Schema(description = "对方用户ID", example = "2")
    private Long userId;

    /** 对方登录用户名。 */
    @Schema(description = "对方登录用户名", example = "lingxi_user")
    private String username;

    /** 对方昵称，页面展示用。 */
    @Schema(description = "对方昵称", example = "灵犀小明")
    private String nickname;

    /** 对方头像地址，空串表示未设置。 */
    @Schema(description = "对方头像地址，空串表示未设置")
    private String avatarUrl;

    /** 成为关注关系的时间（重新关注会刷新）。 */
    @Schema(description = "关注时间")
    private LocalDateTime followedAt;

    /**
     * 当前登录用户是否已关注此人。
     *
     * <p>由业务层批量回填，SQL 不返回该列；调用方未登录时恒为 false。</p>
     */
    @Schema(description = "当前登录用户是否已关注此人", example = "false")
    private Boolean isFollowing;
}
