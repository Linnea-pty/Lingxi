package com.linnea.lingxi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 后台用户列表查询参数：在通用键集分页参数上增加关键字与状态筛选。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "后台用户列表查询参数")
public class AdminUserQueryDTO extends ListQueryDTO {

    /**
     * 用户名/昵称关键字，按前缀匹配。
     *
     * <p>不用 {@code %keyword%} 模糊匹配：前后置通配符会让索引失效退化成全表扫，
     * 而且后台按前缀找人已经够用（运营通常知道用户名开头）。</p>
     */
    @Schema(description = "用户名或昵称前缀关键字，最长 32 字符", example = "lingxi")
    @Size(max = 32, message = "关键字长度不能超过 32 个字符")
    private String keyword;

    /** 账号状态筛选：1-正常 0-封禁，不传表示全部。 */
    @Schema(description = "账号状态筛选：1-正常 0-封禁，不传表示全部", example = "0")
    @Min(value = 0, message = "账号状态只能是 0（封禁）或 1（正常）")
    @Max(value = 1, message = "账号状态只能是 0（封禁）或 1（正常）")
    private Integer status;
}
