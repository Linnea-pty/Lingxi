package com.linnea.lingxi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 通知列表分页结果。
 *
 * <p>键集分页：nextCursor 为空表示已经到底，不要再用页码翻。
 * 同时回传 unreadCount，前端下拉刷新时一次拿全「列表 + 红点数字」。</p>
 */
@Data
@Schema(description = "通知列表分页结果")
public class NotificationPageVO {

    /** 当前页通知列表，可能为空数组。 */
    @Schema(description = "当前页通知列表")
    private List<NotificationVO> notificationList;

    /** 下一页游标，为空表示没有更多。 */
    @Schema(description = "下一页游标，为空表示没有更多", example = "2026-11-17 10:00:00|1024")
    private String nextCursor;

    /** 是否还有下一页。 */
    @Schema(description = "是否还有下一页", example = "true")
    private Boolean hasMore;

    /** 当前未读总数，供红点展示。 */
    @Schema(description = "未读总数", example = "3")
    private Long unreadCount;
}
