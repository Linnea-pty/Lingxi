package com.linnea.lingxi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 点赞人列表分页结果。
 *
 * <p>键集分页不返回总数与页码：翻页只依赖 nextCursor，避免每次翻页都做 COUNT。</p>
 */
@Data
@Schema(description = "点赞人列表分页结果")
public class LikeUserPageVO {

    /** 当前页点赞人列表，可能为空集合（没有更多数据）。 */
    @Schema(description = "当前页点赞人列表，可能为空集合")
    private List<LikeUserVO> userList;

    /** 下一页游标：原样回传 cursor 参数；为 null 表示已到末页。 */
    @Schema(description = "下一页游标，为 null 表示已到末页")
    private String nextCursor;

    /** 是否还有下一页。 */
    @Schema(description = "是否还有下一页", example = "true")
    private Boolean hasMore;
}
