package com.linnea.lingxi.vo;

import com.linnea.lingxi.common.enums.UserRole;
import com.linnea.lingxi.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用户对外信息（View Object）。
 *
 * <p>刻意不含 passwordHash、apiKey、isDeleted 等字段：Entity 永不出 Controller，
 * 新增敏感列时无需担心被顺带序列化出去。</p>
 */
@Data
@Schema(description = "用户对外信息")
public class UserVO {

    /** 用户ID。 */
    @Schema(description = "用户ID", example = "1")
    private Long userId;

    /** 登录用户名。 */
    @Schema(description = "登录用户名", example = "lingxi_user")
    private String username;

    /** 昵称，页面展示用。 */
    @Schema(description = "昵称", example = "灵犀小明")
    private String nickname;

    /** 头像地址，空串表示未设置。 */
    @Schema(description = "头像地址，空串表示未设置")
    private String avatarUrl;

    /** 角色编码：USER / ADMIN，前端据此决定是否展示「后台管理」入口。 */
    @Schema(description = "角色编码：USER-普通用户 ADMIN-管理员", example = "USER")
    private String role;

    /** 角色中文描述，可直接展示。 */
    @Schema(description = "角色中文描述", example = "普通用户")
    private String roleDescription;

    /**
     * 由用户实体转换为对外 VO，敏感字段不拷贝。
     *
     * @param user 用户实体，不可为空
     * @return 对外用户信息
     */
    public static UserVO from(User user) {
        UserVO userVO = new UserVO();
        userVO.setUserId(user.getId());
        userVO.setUsername(user.getUsername());
        userVO.setNickname(user.getNickname());
        // 步骤1：头像列在库中默认空串，刚插入的实体尚未回读为 null，统一归一为空串以免前端判空
        userVO.setAvatarUrl(user.getAvatarUrl() == null ? "" : user.getAvatarUrl());
        // 步骤2：角色编码与描述一并返回，未回读实体的场景（注册/登录刚插入）按普通用户兜底
        UserRole userRole = UserRole.fromCode(user.getRole());
        userVO.setRole(userRole.getCode());
        userVO.setRoleDescription(userRole.getDescription());
        return userVO;
    }
}
