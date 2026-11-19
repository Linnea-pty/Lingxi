package com.linnea.lingxi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改个人资料请求参数（Data Transfer Object）。
 *
 * <p>刻意只有 nickname：头像必须走上传接口（服务端校验类型/大小后落盘），
 * 若这里接受 avatarUrl 字符串，等于开放任意外链注入与 XSS 面。
 * 用户名不可改（它是登录凭据且注销后不释放）。</p>
 */
@Data
@Schema(description = "修改个人资料请求参数")
public class UpdateProfileDTO {

    /** 新昵称：2~20 字符，首尾空白由服务端裁掉后校验。 */
    @Schema(description = "新昵称，2~20 字符", example = "灵犀小明", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "昵称不能为空")
    @Size(min = 2, max = 20, message = "昵称长度需在 2~20 个字符之间")
    private String nickname;
}
