package com.linnea.lingxi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改动态请求参数。
 *
 * <p>只允许改正文：来源、置顶、作者都不接受前端传入——发布时由身份决定，
 * 修改时同样不应成为可篡改字段，否则普通用户可以把动态改成「Agent 置顶」。</p>
 */
@Data
@Schema(description = "修改动态请求参数")
public class UpdatePostDTO {

    /** 新正文：必填，首尾空白由服务层裁剪，纯空白视为未填写。 */
    @Schema(description = "动态正文，不能为空白", example = "把信息流改成了键集分页，深翻页不再变慢",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "动态正文不能为空")
    @Size(max = 2000, message = "动态正文不能超过 2000 字符")
    private String content;
}
