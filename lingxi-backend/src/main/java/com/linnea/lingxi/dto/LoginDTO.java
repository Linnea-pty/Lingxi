package com.linnea.lingxi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求参数。
 *
 * <p>此处只校验非空，长度与格式校验放在注册侧：登录时对格式过于严格的提示会帮助攻击者
 * 缩小枚举范围。</p>
 */
@Data
@Schema(description = "登录请求参数")
public class LoginDTO {

    /** 登录用户名：区分大小写，必须与注册时的大小写完全一致。 */
    @Schema(description = "登录用户名，区分大小写，须与注册时一致", example = "lingxi_user",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "用户名不能为空")
    private String username;

    /** 登录密码明文，仅用于与库中哈希比对，不落库、不打日志。 */
    @Schema(description = "登录密码", example = "P@ssw0rd123", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "密码不能为空")
    private String password;
}
