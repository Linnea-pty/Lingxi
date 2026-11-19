package com.linnea.lingxi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 登录结果（注册成功与刷新令牌换发同样返回本结构）。
 *
 * <p>访问令牌用于每次请求鉴权，刷新令牌只能调换发接口，且每次换发都会作废旧刷新令牌；
 * 前端在访问令牌过期（HTTP 401）时用刷新令牌换一套新的，换发失败才跳登录页。</p>
 */
@Data
@Schema(description = "登录结果")
public class LoginVO {

    /** 访问令牌：后续请求放入请求头 Authorization: Bearer &lt;token&gt;。 */
    @Schema(description = "访问令牌，放入请求头 Authorization: Bearer <token>")
    private String accessToken;

    /** 访问令牌有效期，单位毫秒，供前端安排刷新时机。 */
    @Schema(description = "访问令牌有效期，单位毫秒", example = "7200000")
    private Long expiresIn;

    /** 刷新令牌：只用于 POST /api/v1/auth/refresh，禁止当作访问令牌使用。 */
    @Schema(description = "刷新令牌，只用于换发接口，每次换发后旧令牌立即失效")
    private String refreshToken;

    /** 刷新令牌有效期，单位毫秒。 */
    @Schema(description = "刷新令牌有效期，单位毫秒", example = "604800000")
    private Long refreshExpiresIn;

    /** 当前登录用户信息。 */
    @Schema(description = "当前登录用户信息")
    private UserVO userInfo;
}
