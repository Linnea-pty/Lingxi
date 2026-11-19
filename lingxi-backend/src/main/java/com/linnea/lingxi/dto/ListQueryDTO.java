package com.linnea.lingxi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 通用列表查询参数（键集分页）。
 *
 * <p>与信息流查询的区别只在语义：信息流有「置顶优先」的复合游标，本参数用于
 * 点赞人列表、我赞过的动态、我的评论这类单排序键列表，游标统一是 {@code (create_time, id)} 两列编码。
 * 单独建类而不是复用 FeedQueryDTO：两者 Swagger 描述与后续扩展（如是否需要置顶）会分叉，
 * 复用会让接口文档说谎。</p>
 */
@Data
@Schema(description = "列表查询参数（键集分页）")
public class ListQueryDTO {

    /** 分页游标：上一页返回的 nextCursor 原样回传；首页不传。 */
    @Schema(description = "分页游标，取上一页返回的 nextCursor；首页不传",
            example = "MjAyNi0xMS0xN1QwMzo0MDowMHwxMjM")
    @Size(max = 256, message = "分页游标长度非法")
    private String cursor;

    /** 每页条数：1-30，未传时默认 10。 */
    @Schema(description = "每页条数，1-30，缺省 10", example = "10", defaultValue = "10")
    @Min(value = 1, message = "每页条数不能小于 1")
    @Max(value = 30, message = "每页条数不能大于 30")
    private Integer size;
}
