package com.linnea.lingxi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 讨论串回复数聚合结果。
 *
 * <p>一级评论列表需要展示每条评论的回复数，若逐条再查会产生 N+1；
 * 本结构用于一次 GROUP BY 批量取回当前页所有根评论的回复数。</p>
 */
@Data
@Schema(description = "讨论串回复数聚合结果")
public class CommentReplyCountVO {

    /** 讨论串根评论ID。 */
    @Schema(description = "讨论串根评论ID", example = "1")
    private Long rootId;

    /** 该讨论串下的回复条数。 */
    @Schema(description = "该讨论串下的回复条数", example = "3")
    private Long replyCount;
}
