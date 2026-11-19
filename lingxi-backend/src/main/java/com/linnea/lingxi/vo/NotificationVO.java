package com.linnea.lingxi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知列表项。
 *
 * <p>同时给出 type（跳转分支用）与 typeDescription（直接展示用），
 * 前端不必维护一份「类型 → 文案」的映射表。</p>
 */
@Data
@Schema(description = "通知列表项")
public class NotificationVO {

    /** 通知ID，标记已读时回传。 */
    @Schema(description = "通知ID", example = "1024")
    private Long notificationId;

    /** 通知类型 code：LIKE_POST / COMMENT_POST / REPLY_COMMENT / FOLLOW。 */
    @Schema(description = "通知类型", example = "LIKE_POST",
            allowableValues = {"LIKE_POST", "COMMENT_POST", "REPLY_COMMENT", "FOLLOW"})
    private String type;

    /** 通知类型中文描述，可直接展示。 */
    @Schema(description = "通知类型描述", example = "赞了你的动态")
    private String typeDescription;

    /** 触发者用户ID，点击可跳转其主页。 */
    @Schema(description = "触发者用户ID", example = "2")
    private Long actorId;

    /** 触发者用户名。 */
    @Schema(description = "触发者用户名", example = "lingxi_user")
    private String actorUsername;

    /** 触发者昵称。 */
    @Schema(description = "触发者昵称", example = "灵犀小明")
    private String actorNickname;

    /** 触发者头像地址，未设置为空串。 */
    @Schema(description = "触发者头像地址，未设置为空串", example = "/uploads/avatar/2/xx.png")
    private String actorAvatarUrl;

    /** 业务目标ID：点赞/评论类为目标ID，关注类为发起关注的人。 */
    @Schema(description = "业务目标ID：评论类为评论ID，关注类为发起关注的人", example = "88")
    private Long targetId;

    /** 关联动态ID，0 表示与动态无关（如关注通知）。 */
    @Schema(description = "关联动态ID，0 表示与动态无关", example = "88")
    private Long postId;

    /** 已读标记，false 时前端展示小红点。 */
    @Schema(description = "是否已读", example = "false")
    private Boolean isRead;

    /** 通知时间（评论/点赞/关注的触发时间）。 */
    @Schema(description = "通知时间", example = "2026-11-17 10:00:00")
    private LocalDateTime createTime;
}
