package com.linnea.lingxi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 关注/取关操作结果（View Object）。
 *
 * <p>返回「操作后的状态 + 最新粉丝数」：重复关注/取关不报错，
 * 前端直接用返回值覆盖按钮状态与计数，无需区分首次还是重复。</p>
 */
@Data
@Schema(description = "关注/取关操作结果")
public class FollowResultVO {

    /** 操作后的关注状态：true-已关注 false-未关注。 */
    @Schema(description = "操作后的关注状态", example = "true")
    private Boolean following;

    /** 操作后被关注方的最新粉丝数。 */
    @Schema(description = "被关注方最新粉丝数", example = "12")
    private Long followerCount;
}
