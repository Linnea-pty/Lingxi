package com.linnea.lingxi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用户主页资料（View Object）。
 *
 * <p>与 {@link UserVO} 的分工：UserVO 是「登录态里的我」，只含基础身份字段；
 * 本类面向「看别人的主页」，额外带关注/粉丝计数与我的关注状态，
 * 避免把统计字段塞进登录响应导致每处调用都要多查两次计数。</p>
 */
@Data
@Schema(description = "用户主页资料")
public class UserProfileVO {

    /** 用户ID。 */
    @Schema(description = "用户ID", example = "2")
    private Long userId;

    /** 登录用户名。 */
    @Schema(description = "登录用户名", example = "lingxi_user")
    private String username;

    /** 昵称，页面展示用。 */
    @Schema(description = "昵称", example = "灵犀小明")
    private String nickname;

    /** 头像地址，空串表示未设置。 */
    @Schema(description = "头像地址，空串表示未设置")
    private String avatarUrl;

    /** 粉丝数（关注了该用户的人数）。 */
    @Schema(description = "粉丝数", example = "12")
    private Long followerCount;

    /** 关注数（该用户关注了多少人）。 */
    @Schema(description = "关注数", example = "8")
    private Long followingCount;

    /** 当前登录用户是否已关注该用户；看自己的主页时恒为 false（不能关注自己）。 */
    @Schema(description = "当前登录用户是否已关注该用户", example = "true")
    private Boolean isFollowing;
}
