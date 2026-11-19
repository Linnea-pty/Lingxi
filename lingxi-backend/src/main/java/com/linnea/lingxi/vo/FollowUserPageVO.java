package com.linnea.lingxi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 关注/粉丝列表分页结果（View Object）。
 *
 * <p>键集分页约定与信息流一致：前端只认 hasMore + nextCursor，不自己算页码。</p>
 */
@Data
@Schema(description = "关注/粉丝列表分页结果")
public class FollowUserPageVO {

    /** 本页用户列表，可能为空集合（不会为 null）。 */
    @Schema(description = "本页用户列表")
    private List<FollowUserVO> userList;

    /** 下一页游标，null 表示没有下一页。 */
    @Schema(description = "下一页游标，null 表示没有下一页")
    private String nextCursor;

    /** 是否还有下一页。 */
    @Schema(description = "是否还有下一页", example = "true")
    private Boolean hasMore;
}
