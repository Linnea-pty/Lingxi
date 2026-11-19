package com.linnea.lingxi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 后台用户列表分页结果：与其它列表接口保持同一套键集分页协议。
 */
@Data
@Schema(description = "后台用户列表分页结果")
public class AdminUserPageVO {

    /** 当前页用户列表，按注册时间倒序。 */
    @Schema(description = "当前页用户列表，按注册时间倒序")
    private List<AdminUserVO> userList;

    /** 下一页游标，null 表示没有下一页。 */
    @Schema(description = "下一页游标，null 表示没有下一页")
    private String nextCursor;

    /** 是否还有下一页。 */
    @Schema(description = "是否还有下一页")
    private Boolean hasMore;
}
