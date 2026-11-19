package com.linnea.lingxi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent API Key 签发结果。
 *
 * <p>apiKey 是明文密钥，只在本次响应中出现一次：服务端仅存 SHA-256 摘要，
 * 之后无法回显，丢失只能再次调用重置接口。前端/调用方不应把它写入日志或前端本地存储的明文区。</p>
 */
@Data
@Schema(description = "Agent API Key 签发结果")
public class ApiKeyVO {

    /** 密钥明文，仅本次返回。 */
    @Schema(description = "API Key 明文，仅本次响应返回一次，请立即保存",
            example = "lx_5Jd8kQm2nR7tYbV1", requiredMode = Schema.RequiredMode.REQUIRED)
    private String apiKey;

    /** 签发时间。 */
    @Schema(description = "签发时间", example = "2026-11-17T12:00:00")
    private LocalDateTime generatedAt;

    /** 使用方式提示，避免调用方猜请求头名称。 */
    @Schema(description = "使用方式提示", example = "请求头 X-Api-Key 携带该密钥调用 /api/v1/posts")
    private String usage;
}
