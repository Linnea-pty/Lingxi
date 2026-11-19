package com.linnea.lingxi.common.exception;

/**
 * 业务异常：表示「入参合法但业务规则不允许」的可预期失败，例如用户名已被占用。
 *
 * <p>由全局异常处理器转成 HTTP 200 + 错误码返回，日志级别为 WARN，不打印堆栈。</p>
 */
public class BusinessException extends BaseException {

    /**
     * 使用错误码默认文案构造业务异常。
     *
     * @param errorCode 错误码枚举，不可为空
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getCode(), errorCode.getDefaultMessage());
    }

    /**
     * 使用自定义文案构造业务异常（同一错误码需要补充上下文时使用）。
     *
     * @param errorCode 错误码枚举，不可为空
     * @param message   面向用户的提示语，不可为空
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(errorCode.getCode(), message);
    }

    /**
     * 使用自定义文案构造业务异常并保留根因。
     *
     * @param errorCode 错误码枚举，不可为空
     * @param message   面向用户的提示语，不可为空
     * @param cause     触发本异常的原始异常，可为 null
     */
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode.getCode(), message, cause);
    }
}
