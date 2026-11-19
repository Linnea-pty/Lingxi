package com.linnea.lingxi.common.enums;

import lombok.Getter;

/**
 * 消息通知类型：入库时存 code，出参同时给出 code 与中文描述。
 *
 * <p>新增类型只能追加，不改既有 code 语义——前端可能按 code 做分支跳转。</p>
 */
@Getter
public enum NotificationType {

    /** 赞了你的动态：target_id 为动态ID，post_id 同值。 */
    LIKE_POST("LIKE_POST", "赞了你的动态"),

    /** 评论了你的动态：target_id 为评论ID，post_id 为所属动态。 */
    COMMENT_POST("COMMENT_POST", "评论了你的动态"),

    /** 回复了你的评论：target_id 为回复评论ID，post_id 为所属动态。 */
    REPLY_COMMENT("REPLY_COMMENT", "回复了你的评论"),

    /** 关注了你：target_id 为关注发起方用户ID，post_id 为 0。 */
    FOLLOW("FOLLOW", "关注了你");

    /** 入库值：英文大写 + 下划线，VARCHAR(32) 内。 */
    private final String code;

    /** 中文描述：直接展示给用户，如「赞了你的动态」。 */
    private final String description;

    /**
     * 构造通知类型。
     *
     * @param code        入库值
     * @param description 中文描述
     */
    NotificationType(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
