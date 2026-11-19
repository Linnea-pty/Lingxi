package com.linnea.lingxi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 点赞人信息（View Object）。
 *
 * <p>只暴露展示所需字段：不返回 apiKey、密码哈希等账号敏感列。
 * 与 {@link UserVO} 分开而不复用：这里多一个 likedAt（点赞时间），
 * 且列表口径要求 username 也展示，UserVO 的字段集后续可能变化，耦合会让列表跟着漂移。</p>
 */
@Data
@Schema(description = "点赞人信息")
public class LikeUserVO {

    /** 点赞人用户ID。 */
    @Schema(description = "点赞人用户ID", example = "2")
    private Long userId;

    /** 点赞人登录名：列表需要区分同名昵称，故一并返回。 */
    @Schema(description = "点赞人登录名", example = "lingxi_xiaoming")
    private String username;

    /** 点赞人昵称。 */
    @Schema(description = "点赞人昵称", example = "灵犀小明")
    private String nickname;

    /** 点赞人头像地址：未设置时为空串，不为 null。 */
    @Schema(description = "点赞人头像地址，未设置时为空串")
    private String avatarUrl;

    /** 点赞时间：键集分页的排序键，前端可用于展示「赞于何时」。 */
    @Schema(description = "点赞时间", example = "2026-11-17T03:40:00")
    private LocalDateTime likedAt;
}
