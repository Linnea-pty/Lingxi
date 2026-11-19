package com.linnea.lingxi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 信息流分页结果。
 *
 * <p>键集分页不返回总数与页码：翻页只依赖 nextCursor，
 * 因此不会产生 COUNT 查询，深翻页性能稳定。</p>
 */
@Data
@Schema(description = "信息流分页结果")
public class FeedPageVO {

    /** 当前页动态列表，可能为空集合（没有更多数据）。 */
    @Schema(description = "当前页动态列表，可能为空集合")
    private List<PostVO> postList;

    /** 下一页游标：原样回传给 /feed 的 cursor 参数；为 null 表示已到末页。 */
    @Schema(description = "下一页游标，为 null 表示已到末页")
    private String nextCursor;

    /** 是否还有下一页。 */
    @Schema(description = "是否还有下一页", example = "true")
    private Boolean hasMore;
}
