package com.linnea.lingxi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 评论列表查询参数：键集分页，不返回总数与页码。
 *
 * <p>cursor 由服务端生成、对前端不透明，只需把上一页的 nextCursor 原样回传；
 * size 上限 30 与信息流一致，防止单次拉取过大拖垮响应。</p>
 */
@Data
@Schema(description = "评论列表查询参数")
public class CommentQueryDTO {

    /** 分页游标：为空表示第一页。 */
    @Schema(description = "分页游标，回传上一页的 nextCursor；为空表示第一页")
    private String cursor;

    /** 每页条数：缺省由服务端取 10。 */
    @Schema(description = "每页条数，缺省 10", example = "10", defaultValue = "10")
    @Min(value = 1, message = "每页条数不能小于 1")
    @Max(value = 30, message = "每页条数不能大于 30")
    private Integer size;
}
