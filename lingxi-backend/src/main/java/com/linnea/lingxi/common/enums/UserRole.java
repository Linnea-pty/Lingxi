package com.linnea.lingxi.common.enums;

import lombok.Getter;

/**
 * 用户角色：决定认证签发时授予哪些 Spring Security 权限。
 *
 * <p>只有两级：普通用户与管理员。角色的唯一作用域是「能不能进后台」，
 * 不代表业务能力差异——普通用户能做的操作，管理员不需要额外一套接口。</p>
 */
@Getter
public enum UserRole {

    /** 普通用户：网页端登录后的默认角色，可发布动态、评论、点赞、关注。 */
    USER("USER", "普通用户"),

    /** 管理员：可进入后台治理内容与账号，接口上通过 {@code hasRole('ADMIN')} 约束。 */
    ADMIN("ADMIN", "管理员");

    /** 角色编码，与 lingxi.user.role 列的取值一一对应。 */
    private final String code;

    /** 角色中文描述，用于后台列表展示。 */
    private final String description;

    /**
     * 构造角色枚举。
     *
     * @param code        角色编码，对应数据库列值
     * @param description 角色中文描述
     */
    UserRole(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 按编码解析角色。
     *
     * <p>WHY 未知值统一降级为普通用户：角色是提权开关，遇到脏数据（手改库写错、未来新增角色但本版本未识别）
     * 必须按权限最小化处理，绝不能让未知字符串等价于管理员。</p>
     *
     * @param roleCode 角色编码，可为 null
     * @return 命中的角色；未知或为空时返回 {@link #USER}
     */
    public static UserRole fromCode(String roleCode) {
        // 步骤1：线性遍历匹配（只有两个值，无需建 Map）
        for (UserRole role : values()) {
            if (role.code.equals(roleCode)) {
                return role;
            }
        }
        // 步骤2：未命中按最小权限兜底
        return USER;
    }
}
