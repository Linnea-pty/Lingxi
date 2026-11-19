package com.linnea.lingxi.common.web;

import com.linnea.lingxi.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * 统一返回体：全部 HTTP 接口（含过滤器层的 401/403）对外输出的唯一结构。
 *
 * <p>约定：HttpStatus 只表达传输层语义（200 正常、401 未认证、403 无权限、500 系统故障），
 * 业务成败一律看 code —— code 为 "0" 表示成功，其余为 {@link ErrorCode} 中的错误码；
 * 前端按 code 分支处理，不解析 message。</p>
 *
 * @param <T> 业务数据类型；无业务数据时为 {@link Void}
 */
@Getter
@Schema(description = "统一返回体")
public class Response<T> {

    /** 成功码：固定为 "0"，前端据此判定成功。 */
    public static final String SUCCESS_CODE = "0";

    /** 成功默认提示语。 */
    public static final String SUCCESS_MESSAGE = "success";

    /** 业务状态码："0" 成功，其余为错误码（见 ErrorCode 枚举）。 */
    @Schema(description = "业务状态码：0-成功，其余为错误码", example = "0")
    private final String code;

    /** 提示信息：可直接展示给用户的中文文案；成功时为 success。 */
    @Schema(description = "提示信息，可直接展示给用户", example = "success")
    private final String message;

    /** 业务数据：查询类接口返回业务对象，写操作无返回时为 null。 */
    @Schema(description = "业务数据，无数据时为 null")
    private final T data;

    /**
     * 构造返回体，仅供本类静态工厂调用。
     *
     * @param code    业务状态码，不可为空
     * @param message 提示信息，不可为空
     * @param data    业务数据，可为 null
     */
    private Response(String code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 构造成功响应并携带业务数据。
     *
     * @param data 业务数据，可为 null（表示成功但无数据）
     * @param <T>  业务数据类型
     * @return code 为 "0" 的成功响应
     */
    public static <T> Response<T> success(T data) {
        return new Response<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    /**
     * 构造无业务数据的成功响应（写操作常用）。
     *
     * @return code 为 "0"、data 为 null 的成功响应
     */
    public static Response<Void> success() {
        return new Response<>(SUCCESS_CODE, SUCCESS_MESSAGE, null);
    }

    /**
     * 按错误码构造失败响应，提示语取错误码的默认文案。
     *
     * @param errorCode 错误码枚举，不可为空
     * @param <T>       业务数据类型（失败时恒为 null，仅为保持泛型签名一致）
     * @return 失败响应
     */
    public static <T> Response<T> error(ErrorCode errorCode) {
        return new Response<>(errorCode.getCode(), errorCode.getDefaultMessage(), null);
    }

    /**
     * 按错误码与自定义提示语构造失败响应。
     *
     * @param errorCode 错误码枚举，不可为空
     * @param message   面向用户的提示语，不可为空
     * @param <T>       业务数据类型（失败时恒为 null）
     * @return 失败响应
     */
    public static <T> Response<T> error(ErrorCode errorCode, String message) {
        return new Response<>(errorCode.getCode(), message, null);
    }
}
