package com.linnea.lingxi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 点赞操作结果。
 *
 * <p>点赞与取消点赞都返回「操作后的状态 + 最新总数」：重复点赞不会报错，
 * 而是返回当前真实状态，前端据此校准按钮与计数，不必区分「首次」与「重复」。</p>
 */
@Data
@Schema(description = "点赞操作结果")
public class LikeResultVO {

    /** 当前用户对该条动态的点赞状态。 */
    @Schema(description = "当前用户是否已点赞", example = "true")
    private Boolean liked;

    /** 该动态的有效点赞总数。 */
    @Schema(description = "该动态的有效点赞总数", example = "12")
    private Long likeCount;

    /**
     * 组装点赞结果。
     *
     * @param liked     操作后的点赞状态
     * @param likeCount 操作后的有效点赞总数
     * @return 点赞结果
     */
    public static LikeResultVO of(boolean liked, long likeCount) {
        LikeResultVO likeResult = new LikeResultVO();
        likeResult.setLiked(liked);
        likeResult.setLikeCount(likeCount);
        return likeResult;
    }
}
