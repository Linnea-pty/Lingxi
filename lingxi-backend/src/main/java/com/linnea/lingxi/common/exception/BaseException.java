package com.linnea.lingxi.common.exception;

import lombok.Getter;

/**
 * 项目异常根基类：承载错误码，供全局异常处理器转换为统一返回体。
 *
 * <p>继承 {@link RuntimeException}，避免 checked 异常污染各层方法签名。</p>
 */
@Getter
public class BaseException extends RuntimeException {

    /** 错误码：与 {@link ErrorCode#getCode()} 对应，前端按此值判断失败原因。 */
    private final String code;

    /**
     * 构造异常。
     *
     * @param code    错误码标识，不可为空
     * @param message 面向用户的提示语，不可为空
     */
    public BaseException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 构造异常并保留根因堆栈。
     *
     * @param code    错误码标识，不可为空
     * @param message 面向用户的提示语，不可为空
     * @param cause   触发本异常的原始异常，可为 null
     */
    public BaseException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
