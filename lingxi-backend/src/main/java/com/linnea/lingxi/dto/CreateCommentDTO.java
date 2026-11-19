package com.linnea.lingxi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发表评论请求参数。
 *
 * <p>只接收正文、父评论ID与幂等键：评论人、所属动态、讨论串根ID（root_id）与被回复人全部由服务端推导，
 * 不接受前端传入，避免伪造讨论串归属或冒充他人回复。</p>
 */
@Data
@Schema(description = "发表评论请求参数")
public class CreateCommentDTO {

    /** 评论正文：必填，最长 1000 字符；落库前去除首尾空白。 */
    @Schema(description = "评论正文，最长 1000 字符", example = "键集分页这段注释写得很清楚",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "评论正文不能为空")
    @Size(max = 1000, message = "评论正文不能超过 1000 个字符")
    private String content;

    /** 父评论ID：0 或不传表示对动态发表一级评论；大于 0 表示回复该条评论。 */
    @Schema(description = "父评论ID，0 或不传表示一级评论", example = "0", defaultValue = "0")
    @Min(value = 0, message = "父评论ID不能为负数")
    private Long parentId;

    /** 幂等键：客户端为同一次评论动作生成的唯一标识，重复提交相同值只落一条评论；不传则不去重。 */
    @Schema(description = "幂等键，同一次评论动作重复提交时传相同值可避免重复落库", example = "cmt-20261117-0001")
    @Size(max = 64, message = "幂等键不能超过 64 个字符")
    @Pattern(regexp = "^[A-Za-z0-9_-]*$", message = "幂等键只能包含字母、数字、下划线与中划线")
    private String requestId;
}
