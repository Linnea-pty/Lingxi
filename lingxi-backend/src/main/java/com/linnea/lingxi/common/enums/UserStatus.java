package com.linnea.lingxi.common.enums;

import lombok.Getter;

/**
 * 账号状态：封禁后该账号的旧访问令牌、刷新令牌与 Agent 密钥全部失效。
 *
 * <p>封禁是「保留数据、禁止登录」的运营手段：账号行仍在库中，它发过的动态与评论继续展示，
 * 只是不能再做任何写入，也不能再登录。与「注销」（逻辑删除）刻意区分。</p>
 */
@Getter
public enum UserStatus {

    /** 正常：可登录、可调用所有有权接口。 */
    NORMAL(1, "正常"),

    /** 封禁：认证环节拒绝，登录与刷新令牌换发同样拒绝。 */
    BANNED(0, "封禁");

    /** 状态编码，对应 lingxi.user.status 列的取值。 */
    private final Integer code;

    /** 状态中文描述，用于后台列表展示。 */
    private final String description;

    /**
     * 构造状态枚举。
     *
     * @param code        状态编码，对应数据库列值
     * @param description 状态中文描述
     */
    UserStatus(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 判断给定状态值是否表示「封禁」。
     *
     * <p>WHY 只认封禁值、不把未知值当封禁：列定义是 NOT NULL DEFAULT 1，
     * 若把 null/未知值判为封禁，一次迁移遗漏就会把全站账号锁死；而漏判一个未知值最多是「该账号仍可用」，
     * 由后台列表的 status 展示与人工复核发现。</p>
     *
     * @param statusCode 状态值，可为 null
     * @return true 表示已封禁
     */
    public static boolean isBanned(Integer statusCode) {
        // 步骤1：与封禁编码做等值比较，null 与未知值一律视为未封禁
        return BANNED.code.equals(statusCode);
    }
}
