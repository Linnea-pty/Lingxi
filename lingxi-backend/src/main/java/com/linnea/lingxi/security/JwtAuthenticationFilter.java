package com.linnea.lingxi.security;

import com.linnea.lingxi.common.enums.UserRole;
import com.linnea.lingxi.common.enums.UserStatus;
import com.linnea.lingxi.common.exception.ErrorCode;
import com.linnea.lingxi.entity.User;
import com.linnea.lingxi.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 访问令牌认证过滤器：从 Authorization 请求头解析 Bearer 令牌，校验通过后写入 SecurityContext。
 *
 * <p>令牌通过只说明签名有效，还必须回查账号：用户被注销（逻辑删除）后旧令牌立即失效。
 * 另外每个请求都要查一次吊销标记（Redis），否则登出只是客户端丢弃令牌，被截获的旧令牌依旧能用。
 * 本过滤器内的任何失败都必须自行输出 401 响应，异常若逸出会变成 500 并绕开统一返回体。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 认证请求头名称。 */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /** Bearer 认证方案前缀，注意尾部空格。 */
    private static final String BEARER_PREFIX = "Bearer ";

    /** 普通用户角色：网页端登录身份，与 Agent 的 ROLE_AGENT 区分。 */
    private static final String DEFAULT_ROLE = "ROLE_USER";

    /** 管理员角色：后台接口按 {@code hasRole('ADMIN')} 判定，只有 lingxi.user.role='ADMIN' 的账号才会拿到。 */
    private static final String ADMIN_ROLE = "ROLE_ADMIN";

    /** 令牌签发与校验组件。 */
    private final JwtTokenProvider jwtTokenProvider;

    /** 用户数据访问：用于每个请求回查账号状态。 */
    private final UserMapper userMapper;

    /** 认证失败响应输出器：保证 401 返回体与业务接口同构。 */
    private final AuthenticationErrorWriter authenticationErrorWriter;

    /** 令牌登记与吊销组件：登出后的访问令牌在有效期内必须立刻失效。 */
    private final TokenStore tokenStore;

    /**
     * 解析令牌并建立认证上下文；无令牌时放行给后续链处理（公共接口允许匿名）。
     *
     * @param request     HTTP 请求
     * @param response    HTTP 响应
     * @param filterChain 过滤器链
     * @throws ServletException Servlet 处理异常
     * @throws IOException      IO 异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 步骤1：取认证请求头，缺失或不是 Bearer 方案时直接放行，由授权规则决定是否 401
        String authorizationHeader = request.getHeader(AUTHORIZATION_HEADER);
        if (!StringUtils.hasText(authorizationHeader) || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }
        // 步骤2：解析令牌，签名/有效期/类型任一不合法都按未认证处理，不让异常逸出
        Long userId;
        String tokenId;
        try {
            Claims claims = jwtTokenProvider.parseAccessToken(authorizationHeader.substring(BEARER_PREFIX.length()));
            userId = Long.valueOf(claims.getSubject());
            tokenId = claims.getId();
        } catch (ExpiredJwtException expiredJwtException) {
            log.warn("访问令牌已过期, uri={}", request.getRequestURI());
            authenticationErrorWriter.writeUnauthorized(response, "登录状态已过期，请重新登录");
            return;
        } catch (JwtException | IllegalArgumentException jwtException) {
            log.warn("访问令牌校验失败, uri={}, reason={}", request.getRequestURI(), jwtException.getMessage());
            authenticationErrorWriter.writeUnauthorized(response, ErrorCode.COMMON_UNAUTHORIZED.getDefaultMessage());
            return;
        }
        // 步骤3：检查吊销标记，已登出的令牌在自然过期前一律拒绝（不能只依赖客户端丢弃令牌）
        if (!StringUtils.hasText(tokenId) || tokenStore.isAccessTokenRevoked(tokenId)) {
            log.warn("访问令牌已被吊销或缺少 jti, userId={}, tokenId={}, uri={}", userId, tokenId, request.getRequestURI());
            authenticationErrorWriter.writeUnauthorized(response, "登录状态已失效，请重新登录");
            return;
        }
        // 步骤4：回查账号，已注销或已删除的用户旧令牌立即失效
        User existingUser = userMapper.selectById(userId);
        if (existingUser == null) {
            log.warn("访问令牌对应账号不存在或已注销, userId={}, uri={}", userId, request.getRequestURI());
            authenticationErrorWriter.writeUnauthorized(response, ErrorCode.COMMON_UNAUTHORIZED.getDefaultMessage());
            return;
        }
        // 步骤5：封禁账号的旧令牌立即失效——封禁是运营止损动作，不能等令牌自然过期才生效
        if (UserStatus.isBanned(existingUser.getStatus())) {
            log.warn("访问令牌对应账号已被封禁, userId={}, uri={}", userId, request.getRequestURI());
            authenticationErrorWriter.writeUnauthorized(response, "账号已被封禁，请联系管理员");
            return;
        }
        // 步骤6：写入认证上下文，JWT 认证一定是网页端身份，agent 标记固定为 false，tokenId 供登出时吊销本条令牌
        LoginUser loginUser = new LoginUser(existingUser.getId(), existingUser.getUsername(), false, tokenId);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                loginUser, null, buildAuthorityList(existingUser.getRole()));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        log.debug("访问令牌认证通过, userId={}, role={}, uri={}", userId, existingUser.getRole(), request.getRequestURI());
        // 步骤7：继续执行后续过滤器
        filterChain.doFilter(request, response);
    }

    /**
     * 按账号角色构造权限列表。
     *
     * <p>管理员同时保留 ROLE_USER：后台闸门是 {@code hasRole('ADMIN')}，
     * 若只给 ROLE_ADMIN，管理员访问普通业务接口反而会 403。</p>
     *
     * @param roleCode 账号角色编码，可为 null
     * @return 权限列表，永不为空
     */
    private List<SimpleGrantedAuthority> buildAuthorityList(String roleCode) {
        // 步骤1：管理员授予双角色
        if (UserRole.ADMIN == UserRole.fromCode(roleCode)) {
            log.info("按管理员角色授予权限, authorityCount=2");
            return List.of(new SimpleGrantedAuthority(ADMIN_ROLE), new SimpleGrantedAuthority(DEFAULT_ROLE));
        }
        // 步骤2：普通用户与未知角色一律只给 ROLE_USER（权限最小化）
        log.info("按普通用户角色授予权限, authorityCount=1");
        return List.of(new SimpleGrantedAuthority(DEFAULT_ROLE));
    }
}
