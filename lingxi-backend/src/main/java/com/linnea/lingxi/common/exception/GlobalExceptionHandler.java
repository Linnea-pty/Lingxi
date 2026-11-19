package com.linnea.lingxi.common.exception;

import com.linnea.lingxi.common.web.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 全局异常处理器：把 Controller 层抛出的异常统一转换成 {@link Response}。
 *
 * <p>只兜 Controller 及以下链路；过滤器层（JWT 过滤器、API Key 过滤器）与 Security 入口点抛出的异常不经过本类，
 * 由 {@code AuthenticationErrorWriter} 与 {@code SecurityConfig} 的 EntryPoint/DeniedHandler 输出同样结构。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常：可预期失败，返回 HTTP 200 + 业务错误码，记录 WARN 但不打堆栈。
     *
     * @param businessException 业务异常
     * @return 携带错误码与提示语的失败响应
     */
    @ExceptionHandler(BusinessException.class)
    public Response<Void> handleBusinessException(BusinessException businessException) {
        log.warn("业务异常, code={}, message={}", businessException.getCode(), businessException.getMessage());
        return Response.error(resolveErrorCode(businessException), businessException.getMessage());
    }

    /**
     * 处理系统异常：记录 ERROR 与完整堆栈，对外只返回通用文案，避免泄露技术细节。
     *
     * @param systemException 系统异常
     * @return COMMON_SYSTEM_ERROR 失败响应
     */
    @ExceptionHandler(SystemException.class)
    public Response<Void> handleSystemException(SystemException systemException) {
        log.error("系统异常, code={}", systemException.getCode(), systemException);
        return Response.error(ErrorCode.COMMON_SYSTEM_ERROR);
    }

    /**
     * 处理 Bean Validation 校验失败：汇总全部字段的错误文案，一次性返回给前端。
     *
     * @param methodArgumentNotValidException 参数校验异常
     * @return COMMON_PARAM_ERROR 失败响应，message 为拼接后的字段错误
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Response<Void> handleValidationException(
            MethodArgumentNotValidException methodArgumentNotValidException) {
        // 步骤1：取出所有字段级错误并拼接为一条可读文案
        List<FieldError> fieldErrorList = methodArgumentNotValidException.getBindingResult().getFieldErrors();
        String detailMessage = fieldErrorList.stream()
                .map(FieldError::getDefaultMessage)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("; "));
        // 步骤2：记录被拦截的字段数量与文案，便于定位前端传参问题
        log.warn("参数校验未通过, fieldCount={}, detail={}", fieldErrorList.size(), detailMessage);
        // 步骤3：文案为空时回退到错误码默认文案，保证前端始终有可展示内容
        if (detailMessage.isEmpty()) {
            return Response.error(ErrorCode.COMMON_PARAM_ERROR);
        }
        return Response.error(ErrorCode.COMMON_PARAM_ERROR, detailMessage);
    }

    /**
     * 处理请求体解析失败：JSON 语法错误或字段类型不匹配属于调用方问题，按参数错误返回而非系统错误。
     *
     * @param httpMessageNotReadableException 请求体解析异常
     * @return COMMON_PARAM_ERROR 失败响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Response<Void> handleNotReadableException(
            HttpMessageNotReadableException httpMessageNotReadableException) {
        log.warn("请求体解析失败, 按参数错误处理, reason={}", httpMessageNotReadableException.getMessage());
        return Response.error(ErrorCode.COMMON_PARAM_ERROR, "请求体格式不正确，请检查 JSON 结构与字段类型");
    }

    /**
     * 处理上传文件体积超限：multipart 解析阶段就被容器拦下，此时还没有进入业务代码。
     *
     * <p>不接这个异常的话会落到 Exception 兜底，前端收到 COMMON_SYSTEM_ERROR，
     * 无法据此提示「图片太大，请压缩后重试」。</p>
     *
     * @param maxUploadSizeExceededException 上传体积超限异常
     * @return FILE_TOO_LARGE 失败响应
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Response<Void> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException maxUploadSizeExceededException) {
        // 步骤1：记录容器层配置的上限，便于核对与 AvatarStorage 的业务上限是否一致
        log.warn("上传文件超出体积上限, maxUploadSize={}", maxUploadSizeExceededException.getMaxUploadSize());
        return Response.error(ErrorCode.FILE_TOO_LARGE);
    }

    /**
     * 处理缺少上传文件部分：请求是 multipart，但没有名为 file 的部分。
     *
     * @param missingServletRequestPartException 缺少请求部分异常
     * @return FILE_UPLOAD_EMPTY 失败响应
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public Response<Void> handleMissingRequestPartException(
            MissingServletRequestPartException missingServletRequestPartException) {
        // 步骤1：记录缺失的部分名，便于前端核对表单字段名是否写成 file
        log.warn("上传请求缺少文件部分, partName={}", missingServletRequestPartException.getRequestPartName());
        return Response.error(ErrorCode.FILE_UPLOAD_EMPTY);
    }

    /**
     * 处理方法级权限拒绝：{@code @PreAuthorize} 判定不通过。
     *
     * <p>WHY 必须单独接：方法级权限检查发生在 handler 方法执行阶段，抛出的
     * {@code AuthorizationDeniedException}（{@link AccessDeniedException} 子类）会先被 MVC 异常解析器拿到，
     * 轮不到 Security 的 AccessDeniedHandler；不接就会落到 Exception 兜底，变成「200 + COMMON_SYSTEM_ERROR」，
     * 前端无法据此区分「没权限」与「服务出错」。</p>
     *
     * @param accessDeniedException 权限拒绝异常
     * @return HTTP 403 + COMMON_FORBIDDEN
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Response<Void> handleAccessDeniedException(AccessDeniedException accessDeniedException) {
        log.warn("权限不足, 请求被拒绝, reason={}", accessDeniedException.getMessage());
        return Response.error(ErrorCode.COMMON_FORBIDDEN);
    }

    /**
     * 兜底处理未预期异常：防止堆栈直接返回给前端。
     *
     * @param exception 未预期异常
     * @return COMMON_SYSTEM_ERROR 失败响应
     */
    @ExceptionHandler(Exception.class)
    public Response<Void> handleException(Exception exception) {
        log.error("未预期异常，已兜底为系统错误", exception);
        return Response.error(ErrorCode.COMMON_SYSTEM_ERROR);
    }

    /**
     * 把业务异常携带的错误码字符串还原为枚举，用于复用错误码默认文案。
     *
     * @param businessException 业务异常
     * @return 匹配的错误码枚举；无匹配时返回 COMMON_SYSTEM_ERROR
     */
    private ErrorCode resolveErrorCode(BusinessException businessException) {
        // 步骤1：按 code 精确匹配枚举，保证返回值可被 Response.error 复用
        for (ErrorCode errorCode : ErrorCode.values()) {
            if (errorCode.getCode().equals(businessException.getCode())) {
                return errorCode;
            }
        }
        // 步骤2：未登记的错误码归为系统错误，避免返回无法识别的码值
        log.warn("业务异常使用了未登记的错误码, code={}", businessException.getCode());
        return ErrorCode.COMMON_SYSTEM_ERROR;
    }
}
