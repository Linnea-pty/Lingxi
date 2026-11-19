package com.linnea.lingxi.security;

import com.linnea.lingxi.common.exception.ErrorCode;
import com.linnea.lingxi.common.web.Response;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * 认证失败响应输出器：把 401 写成与业务接口同构的统一返回体。
 *
 * <p>WHY 单独成组件：JWT 过滤器与 API Key 过滤器都需要在鉴权失败时自行输出 401，
 * 若各写一份，返回体结构一旦调整就会漏改一处，前端将收到两种不同格式的 401。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthenticationErrorWriter {

    /** JSON 序列化器：输出统一返回体需要与业务接口一致的字段名。 */
    private final ObjectMapper objectMapper;

    /**
     * 输出 401 统一返回体。
     *
     * @param response HTTP 响应
     * @param message  面向用户的提示语
     * @throws IOException 响应输出失败时抛出
     */
    public void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        // 步骤1：设置 HTTP 状态与 JSON 内容类型，避免前端按 HTML 解析
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // 步骤2：组装与业务接口同构的返回体，前端无需为 401 单独适配结构
        Response<Void> unauthorizedResponse = Response.error(ErrorCode.COMMON_UNAUTHORIZED, message);
        // 步骤3：写入响应流
        response.getWriter().write(objectMapper.writeValueAsString(unauthorizedResponse));
        log.debug("已输出 401 统一返回体, message={}", message);
    }
}
