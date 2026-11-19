package com.linnea.lingxi.config;

import com.linnea.lingxi.common.exception.ErrorCode;
import com.linnea.lingxi.common.web.Response;
import com.linnea.lingxi.security.ApiKeyAuthenticationFilter;
import com.linnea.lingxi.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 配置：无状态 JWT 鉴权、密码编码器与过滤器层错误输出。
 *
 * <p>关键决策：会话无状态（认证信息只来自令牌）、关闭 CSRF（无 Cookie 会话可被 CSRF 利用）、
 * 关闭表单登录与 HttpBasic（前后端分离，登录走 /api/v1/auth/login 接口）。</p>
 */
@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * 匿名可访问的路径白名单：认证入口、接口文档、本地上传的静态资源、Spring 默认错误端点。
     *
     * <p>/api/v1/auth/refresh 也在白名单内：调用它时访问令牌通常已经过期，
     * 换发凭的是请求体里的刷新令牌（服务端登记过的 jti），而不是 Authorization 头。</p>
     *
     * <p>/uploads/** 放行的原因：头像是 &lt;img&gt; 直接加载的公开资源，浏览器不会带 Authorization 头；
     * 它只承载服务端校验过的图片字节（文件名由服务端生成），不含任何用户隐私数据。</p>
     */
    private static final String[] PUBLIC_PATH_ARRAY = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/uploads/**",
            "/error"
    };

    /** BCrypt 强度：10 为安全下限，12 为当前推荐值，强度每 +1 计算耗时翻倍。 */
    private static final int BCRYPT_STRENGTH = 12;

    /** JWT 认证过滤器。 */
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /** Agent API Key 认证过滤器：排在 JWT 之后，仅在尚无认证主体时生效。 */
    private final ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;

    /** JSON 序列化器：过滤器层 401/403 需要手写响应体。 */
    private final ObjectMapper objectMapper;

    /**
     * 密码编码器：注册时哈希、登录时比对，业务层只依赖 PasswordEncoder 接口。
     *
     * @return BCrypt 编码器实例
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        log.info("初始化密码编码器, algorithm=BCrypt, strength={}", BCRYPT_STRENGTH);
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }

    /**
     * 跨域配置源：给浏览器跨域请求补上 CORS 响应头。
     *
     * <p>WHY 需要显式配置：前后端分离开发时，前端 Vite dev 服务器（默认 5173）直连后端
     * （默认 18080），属于跨域；请求携带 Authorization 头或 JSON 体时会触发浏览器预检
     * （OPTIONS）。安全链里对 OPTIONS 的 permitAll 只避免 401/403，并不会补 CORS 头，
     * 没有这些头浏览器照样拦截响应，故必须由本配置源统一返回。</p>
     *
     * @return 基于 URL 的跨域配置源，覆盖全部接口路径
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        // 步骤1：构建跨域规则，覆盖本机回环与局域网私有网段（开发时前端常用 IP 访问，如 172.19.0.1）；
        // 带凭证故用 originPatterns 而非通配 origins——originPatterns 会回显具体 Origin，不会返回 "*"
        CorsConfiguration corsConfiguration = new CorsConfiguration();
        corsConfiguration.setAllowedOriginPatterns(List.of(
                "http://localhost:*", "http://127.0.0.1:*",
                "http://192.168.*.*:*", "http://10.*.*.*:*", "http://172.*.*.*:*"));
        corsConfiguration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        corsConfiguration.setAllowedHeaders(List.of("*"));
        corsConfiguration.setAllowCredentials(true);
        corsConfiguration.setMaxAge(3600L);
        // 步骤2：注册到根路径，覆盖业务接口与 /uploads 静态资源，并返回供安全链挂载
        UrlBasedCorsConfigurationSource corsSource = new UrlBasedCorsConfigurationSource();
        corsSource.registerCorsConfiguration("/**", corsConfiguration);
        log.info("跨域配置完成, allowedOriginPatterns={}, allowedMethods={}",
                corsConfiguration.getAllowedOriginPatterns(), corsConfiguration.getAllowedMethods());
        return corsSource;
    }

    /**
     * 构建安全过滤器链。
     *
     * @param httpSecurity Spring Security 的 HTTP 配置入口
     * @return 安全过滤器链
     * @throws Exception 配置构建失败时抛出
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {
        // 步骤1：启用 CORS 并挂载跨域配置源，预检请求才能拿到 Access-Control-Allow-* 头
        httpSecurity.cors(cors -> cors.configurationSource(corsConfigurationSource()));
        // 步骤2：关闭 CSRF，纯 Bearer 令牌认证不依赖浏览器自动携带的 Cookie
        httpSecurity.csrf(AbstractHttpConfigurer::disable);
        // 步骤3：会话改为无状态，服务端不保存登录态，便于水平扩容
        httpSecurity.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        // 步骤4：关闭表单登录与 HttpBasic，避免绕过统一返回体返回 HTML 登录页
        httpSecurity.formLogin(AbstractHttpConfigurer::disable);
        httpSecurity.httpBasic(AbstractHttpConfigurer::disable);
        // 步骤5：配置路由授权规则，白名单匿名、其余必须认证
        httpSecurity.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(PUBLIC_PATH_ARRAY).permitAll()
                .anyRequest().authenticated());
        // 步骤6：过滤器层的未认证/无权限同样输出统一返回体，不让前端收到 HTML
        httpSecurity.exceptionHandling(exceptionHandling -> exceptionHandling
                .authenticationEntryPoint((request, response, authenticationException) ->
                        writeSecurityError(response, HttpServletResponse.SC_UNAUTHORIZED,
                                ErrorCode.COMMON_UNAUTHORIZED, "未登录或登录状态已失效"))
                .accessDeniedHandler((request, response, accessDeniedException) ->
                        writeSecurityError(response, HttpServletResponse.SC_FORBIDDEN,
                                ErrorCode.COMMON_FORBIDDEN, ErrorCode.COMMON_FORBIDDEN.getDefaultMessage())));
        // 步骤7：JWT 过滤器置于用户名密码认证之前，先建立认证上下文再走授权判定
        httpSecurity.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        // 步骤8：API Key 过滤器排在 JWT 之后；同时携带两种凭证时以 JWT 身份为准
        httpSecurity.addFilterAfter(apiKeyAuthenticationFilter, JwtAuthenticationFilter.class);
        log.info("Spring Security 配置完成, stateless=true, publicPaths={}", Arrays.toString(PUBLIC_PATH_ARRAY));
        return httpSecurity.build();
    }

    /**
     * 禁止 JWT 过滤器被 Servlet 容器重复注册：作为 Spring Bean 的 Filter 默认会被自动挂载一次，
     * 而 Security 链中又显式添加一次，会导致同一请求解析两遍令牌。
     *
     * @param jwtFilter JWT 认证过滤器
     * @return 已禁用的过滤器注册对象
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter jwtFilter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(jwtFilter);
        registration.setEnabled(false);
        log.info("已关闭 JWT 过滤器的 Servlet 自动注册，避免重复执行");
        return registration;
    }

    /**
     * 禁止 API Key 过滤器被 Servlet 容器重复注册，原因同 JWT 过滤器。
     *
     * @param apiKeyFilter Agent API Key 认证过滤器
     * @return 已禁用的过滤器注册对象
     */
    @Bean
    public FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyFilterRegistration(
            ApiKeyAuthenticationFilter apiKeyFilter) {
        FilterRegistrationBean<ApiKeyAuthenticationFilter> registration = new FilterRegistrationBean<>(apiKeyFilter);
        registration.setEnabled(false);
        log.info("已关闭 API Key 过滤器的 Servlet 自动注册，避免重复执行");
        return registration;
    }

    /**
     * 输出过滤器层的 401/403 统一返回体。
     *
     * @param response  HTTP 响应
     * @param httpStatus 传输层状态码
     * @param errorCode  业务错误码
     * @param message    面向用户的提示语
     * @throws IOException 响应输出失败时抛出
     */
    private void writeSecurityError(HttpServletResponse response, int httpStatus,
                                    ErrorCode errorCode, String message) throws IOException {
        // 步骤1：记录被安全链拦截的请求，便于排查鉴权问题（此处无请求对象，只记状态与码值）
        log.warn("安全链拦截请求, httpStatus={}, code={}", httpStatus, errorCode.getCode());
        // 步骤2：设置状态与内容类型后写入统一返回体
        response.setStatus(httpStatus);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(Response.error(errorCode, message)));
    }
}
