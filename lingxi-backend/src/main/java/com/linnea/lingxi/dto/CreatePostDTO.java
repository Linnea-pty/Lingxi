package com.linnea.lingxi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发布动态请求参数。
 *
 * <p>只接收正文与幂等键：发布者、来源、置顶标记与时间均由服务端决定，
 * 不接受前端传入，避免伪造他人发布或自行置顶。</p>
 */
@Data
@Schema(description = "发布动态请求参数")
public class CreatePostDTO {

    /** 动态正文：必填，最长 2000 字符；落库前去除首尾空白，纯空白视为未填写。 */
    @Schema(description = "动态正文，最长 2000 字符", example = "今天把信息流的分页改成了键集分页",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "动态正文不能为空")
    @Size(max = 2000, message = "动态正文不能超过 2000 个字符")
    private String content;

    /** 幂等键：客户端为同一次发布动作生成的唯一标识，重复提交相同值只落一条动态；不传则不去重。 */
    @Schema(description = "幂等键，同一次发布动作重复提交时传相同值可避免重复落库", example = "req-20261117-0001")
    @Size(max = 64, message = "幂等键不能超过 64 个字符")
    @Pattern(regexp = "^[A-Za-z0-9_-]*$", message = "幂等键只能包含字母、数字、下划线与中划线")
    private String requestId;
}
