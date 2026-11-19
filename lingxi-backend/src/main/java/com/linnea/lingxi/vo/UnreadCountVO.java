package com.linnea.lingxi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 未读通知数，供角标轮询使用。
 *
 * <p>单独提供而不复用分页接口：轮询只要一个数字，拉整页通知会白白放大流量。</p>
 */
@Data
@Schema(description = "未读通知数")
public class UnreadCountVO {

    /** 未读条数，无未读时为 0。 */
    @Schema(description = "未读条数，无未读时为 0", example = "3")
    private Long unreadCount;
}
