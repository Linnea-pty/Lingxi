package com.linnea.lingxi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 信息流查询参数（键集分页）。
 *
 * <p>不接受页码：键集分页用游标定位，避免 LIMIT offset 在深翻页时全表扫描。
 * 游标对前端是不透明字符串，取上一页返回的 nextCursor 原样回传即可。</p>
 */
@Data
@Schema(description = "信息流查询参数（键集分页）")
public class FeedQueryDTO {

    /** 分页游标：上一页返回的 nextCursor 原样回传；首页不传，服务端解码失败按参数错误处理。 */
    @Schema(description = "分页游标，取上一页返回的 nextCursor；首页不传",
            example = "MXwyMDI2LTExLTE3VDAzOjQwOjAwfDEyMw")
    @Size(max = 256, message = "分页游标长度非法")
    private String cursor;

    /** 每页条数：1-30，未传时默认 10；比分页插件 maxLimit(100) 更严，业务上限收紧在此处。 */
    @Schema(description = "每页条数，1-30，缺省 10", example = "10", defaultValue = "10")
    @Min(value = 1, message = "每页条数不能小于 1")
    @Max(value = 30, message = "每页条数不能大于 30")
    private Integer size;
}
