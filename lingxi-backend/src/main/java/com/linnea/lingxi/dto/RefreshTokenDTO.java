package com.linnea.lingxi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 换发令牌请求参数。
 *
 * <p>刷新令牌走请求体而不是 Authorization 头：两个令牌用途不同，
 * 混在同一个请求头里极易把刷新令牌当访问令牌发出去。</p>
 */
@Data
@Schema(description = "换发令牌请求参数")
public class RefreshTokenDTO {

    /** 登录时下发的刷新令牌。 */
    @NotBlank(message = "刷新令牌不能为空")
    @Schema(description = "登录时下发的刷新令牌", requiredMode = Schema.RequiredMode.REQUIRED)
    private String refreshToken;
}
