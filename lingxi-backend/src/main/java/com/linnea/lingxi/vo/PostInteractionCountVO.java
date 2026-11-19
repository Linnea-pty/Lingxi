package com.linnea.lingxi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 动态互动计数聚合结果。
 *
 * <p>信息流需要展示每条动态的点赞数与评论数，逐条统计会产生 N+1；
 * 本结构用于一次 IN + 标量子查询批量取回当前页所有动态的计数。</p>
 */
@Data
@Schema(description = "动态互动计数聚合结果")
public class PostInteractionCountVO {

    /** 动态ID。 */
    @Schema(description = "动态ID", example = "1")
    private Long postId;

    /** 点赞数：只统计未取消的点赞。 */
    @Schema(description = "点赞数", example = "8")
    private Long likeCount;

    /** 评论数：含全部回复层级，只统计未删除的评论。 */
    @Schema(description = "评论数（含回复）", example = "3")
    private Long commentCount;
}
