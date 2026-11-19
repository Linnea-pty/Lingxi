package com.linnea.lingxi.vo;

import com.linnea.lingxi.common.enums.UserRole;
import com.linnea.lingxi.common.enums.UserStatus;
import com.linnea.lingxi.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 后台用户列表项：运营视角的账号信息。
 *
 * <p>与 {@link UserVO} 的区别：这里带角色与封禁状态，供后台判断「能不能封」；
 * 同样不含 password_hash 与 api_key，后台也不需要看到密钥摘要。</p>
 */
@Data
@Schema(description = "后台用户列表项")
public class AdminUserVO {

    /** 用户ID。 */
    @Schema(description = "用户ID", example = "2")
    private Long userId;

    /** 登录用户名，全局唯一。 */
    @Schema(description = "登录用户名", example = "lingxi_user")
    private String username;

    /** 昵称，用于展示。 */
    @Schema(description = "昵称", example = "灵犀小明")
    private String nickname;

    /** 头像地址，空串表示未设置。 */
    @Schema(description = "头像地址，空串表示未设置")
    private String avatarUrl;

    /** 角色编码：USER / ADMIN。 */
    @Schema(description = "角色编码：USER-普通用户 ADMIN-管理员", example = "USER")
    private String role;

    /** 角色中文描述，可直接展示。 */
    @Schema(description = "角色中文描述", example = "普通用户")
    private String roleDescription;

    /** 账号状态编码：1-正常 0-封禁。 */
    @Schema(description = "账号状态：1-正常 0-封禁", example = "1")
    private Integer status;

    /** 状态中文描述，可直接展示。 */
    @Schema(description = "状态中文描述", example = "正常")
    private String statusDescription;

    /** 封禁原因，空串表示未封禁。 */
    @Schema(description = "封禁原因，空串表示未封禁")
    private String banReason;

    /** 注册时间。 */
    @Schema(description = "注册时间")
    private LocalDateTime createTime;

    /**
     * 由用户实体转换为后台列表项。
     *
     * @param user 用户实体（来自数据访问层）
     * @return 后台列表项；入参为 null 时返回 null，便于批量转换时保持原样
     */
    public static AdminUserVO from(User user) {
        // 步骤1：空值直接返回，避免调用方每处都判空
        if (user == null) {
            return null;
        }
        // 步骤2：映射基础字段
        AdminUserVO adminUserInfo = new AdminUserVO();
        adminUserInfo.setUserId(user.getId());
        adminUserInfo.setUsername(user.getUsername());
        adminUserInfo.setNickname(user.getNickname());
        adminUserInfo.setAvatarUrl(user.getAvatarUrl());
        adminUserInfo.setRole(user.getRole());
        adminUserInfo.setStatus(user.getStatus());
        adminUserInfo.setBanReason(user.getBanReason());
        adminUserInfo.setCreateTime(user.getCreateTime());
        // 步骤3：把编码翻译成中文描述，前端不必维护映射表
        adminUserInfo.setRoleDescription(UserRole.fromCode(user.getRole()).getDescription());
        adminUserInfo.setStatusDescription(resolveStatusDescription(user.getStatus()));
        return adminUserInfo;
    }

    /**
     * 解析账号状态的中文描述。
     *
     * @param statusCode 状态编码
     * @return 中文描述；未知值原样转成字符串，避免展示空白
     */
    private static String resolveStatusDescription(Integer statusCode) {
        // 步骤1：逐一比对已知状态
        for (UserStatus userStatus : UserStatus.values()) {
            if (userStatus.getCode().equals(statusCode)) {
                return userStatus.getDescription();
            }
        }
        // 步骤2：未知状态降级为原始值，便于人工发现脏数据
        return String.valueOf(statusCode);
    }
}
