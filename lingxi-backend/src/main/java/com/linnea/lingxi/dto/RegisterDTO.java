package com.linnea.lingxi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求参数。
 *
 * <p>只承载注册入口需要的字段，用户ID、密码哈希、创建时间等由服务端生成，
 * 不接受前端传入（防止越权指定主键或直接写入密文）。</p>
 */
@Data
@Schema(description = "注册请求参数")
public class RegisterDTO {

    /** 登录用户名：4-32 位字母/数字/下划线，全局唯一、注销后不释放、比较区分大小写。 */
    @Schema(description = "登录用户名，4-32 位字母、数字或下划线，区分大小写（Alice 与 alice 是两个账号）",
            example = "lingxi_user", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_]{4,32}$", message = "用户名必须为 4-32 位字母、数字或下划线")
    private String username;

    /** 登录密码明文：8-64 位，落库前统一做 BCrypt 哈希，严禁明文存储或回显。 */
    @Schema(description = "登录密码，8-64 位", example = "P@ssw0rd123",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度必须为 8-64 位")
    private String password;

    /** 昵称：可选，空白时服务端默认取用户名。 */
    @Schema(description = "昵称，为空时默认取用户名", example = "灵犀小明")
    @Size(max = 64, message = "昵称长度不能超过 64 个字符")
    private String nickname;
}
