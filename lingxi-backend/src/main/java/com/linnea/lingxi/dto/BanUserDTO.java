package com.linnea.lingxi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 封禁账号请求参数。
 *
 * <p>封禁原因可空：运营在处置突发内容时常常先封再补原因，
 * 强制填原因会让「先止损」这一步被表单挡住；原因只作为备注，不参与任何判定。</p>
 */
@Data
@Schema(description = "封禁账号请求参数")
public class BanUserDTO {

    /** 封禁原因（运营备注），最长 200 字符，可空。 */
    @Schema(description = "封禁原因，最长 200 字符，可空", example = "发布违规内容")
    @Size(max = 200, message = "封禁原因长度不能超过 200 个字符")
    private String reason;
}
