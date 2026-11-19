package com.linnea.lingxi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 动态列表分页结果（非信息流场景）。
 *
 * <p>与 {@link FeedPageVO} 结构相同但语义不同：本结构用于「我赞过的动态」这类
 * 单排序键列表，游标是 {@code (create_time, id)} 两列；信息流游标含 is_pinned 三列。
 * 分开定义是为了让两处游标生成逻辑不共用同一个 VO，避免后续给信息流加排序维度时误伤列表。</p>
 */
@Data
@Schema(description = "动态列表分页结果")
public class PostPageVO {

    /** 当前页动态列表，可能为空集合（没有更多数据）。 */
    @Schema(description = "当前页动态列表，可能为空集合")
    private List<PostVO> postList;

    /** 下一页游标：原样回传 cursor 参数；为 null 表示已到末页。 */
    @Schema(description = "下一页游标，为 null 表示已到末页")
    private String nextCursor;

    /** 是否还有下一页。 */
    @Schema(description = "是否还有下一页", example = "true")
    private Boolean hasMore;
}
