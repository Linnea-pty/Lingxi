package com.linnea.lingxi.common.exception;

/**
 * 系统异常：表示基础设施故障或不可预期状态，例如外部服务不可用、数据库连接失败。
 *
 * <p>由全局异常处理器转成 COMMON_SYSTEM_ERROR，日志级别为 ERROR 且必须带堆栈；
 * 对外只返回通用文案，禁止把技术细节回显给用户。</p>
 */
public class SystemException extends BaseException {

    /**
     * 构造系统异常（无根因时使用，建议同时传入 cause 便于排障）。
     *
     * @param message 内部排障用的技术描述，不会直接返回给前端
     */
    public SystemException(String message) {
        super(ErrorCode.COMMON_SYSTEM_ERROR.getCode(), message);
    }

    /**
     * 构造系统异常并保留根因堆栈。
     *
     * @param message 内部排障用的技术描述，不会直接返回给前端
     * @param cause   触发本异常的原始异常，可为 null
     */
    public SystemException(String message, Throwable cause) {
        super(ErrorCode.COMMON_SYSTEM_ERROR.getCode(), message, cause);
    }
}
