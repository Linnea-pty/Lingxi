package com.linnea.lingxi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 登出请求参数。
 *
 * <p>刷新令牌选填：只带访问令牌也能登出（此时只吊销当前访问令牌）；
 * 一并提交则把刷新令牌也作废，避免客户端登出后刷新令牌仍能换出新令牌。</p>
 */
@Data
@Schema(description = "登出请求参数")
public class LogoutDTO {

    /** 登录时下发的刷新令牌，选填；提交即一并吊销。 */
    @Schema(description = "刷新令牌，选填；提交则一并吊销，防止登出后还能换发新令牌")
    private String refreshToken;
}
