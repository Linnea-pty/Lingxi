package com.linnea.lingxi.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linnea.lingxi.common.enums.UserStatus;
import com.linnea.lingxi.entity.User;
import com.linnea.lingxi.mapper.UserMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Agent API Key 认证过滤器：从 X-Api-Key 请求头识别机器调用方。
 *
 * <p>执行顺序放在 JWT 过滤器之后：两者都携带时以 JWT 身份为准，API Key 只在尚无认证时生效，
 * 避免同一请求出现两个互相覆盖的认证主体。</p>
 *
 * <p>库内只存密钥摘要（见 {@link ApiKeyCodec}），因此校验方式是「对提交值做 SHA-256 后等值查询」。
 * 认证通过后授予 ROLE_AGENT，并把 agent 标记写入 {@link LoginUser}，业务层据此决定代发行为。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    /** Agent 认证请求头名称。 */
    private static final String API_KEY_HEADER = "X-Api-Key";

    /** Agent 角色：与网页端 ROLE_USER 区分，便于后续用 @PreAuthorize 限制机器专属接口。 */
    private static final String AGENT_ROLE = "ROLE_AGENT";

    /** 密钥最大长度：正常密钥约 46 字符，超长串直接拒绝，避免无效摘要计算与索引穿透。 */
    private static final int MAX_API_KEY_LENGTH = 128;

    /** 用户数据访问：按密钥摘要回查账号。 */
    private final UserMapper userMapper;

    /** 认证失败响应输出器：保证 401 返回体与业务接口同构。 */
    private final AuthenticationErrorWriter authenticationErrorWriter;

    /**
     * 校验 API Key 并建立 Agent 认证上下文；未携带该请求头时直接放行。
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
        // 步骤1：未携带 Agent 请求头则放行，普通网页端请求走 JWT 身份
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (!StringUtils.hasText(apiKey)) {
            filterChain.doFilter(request, response);
            return;
        }
        // 步骤2：已有认证（JWT 优先）时不覆盖，避免同时携带两种凭证导致身份歧义
        Authentication existingAuthentication = SecurityContextHolder.getContext().getAuthentication();
        if (existingAuthentication != null && existingAuthentication.isAuthenticated()) {
            log.debug("已存在认证主体, 忽略 API Key, uri={}", request.getRequestURI());
            filterChain.doFilter(request, response);
            return;
        }
        // 步骤3：长度兜底，非法超长串直接按认证失败处理；日志只记长度，不记请求头原值
        int headerValueLength = apiKey.length();
        if (headerValueLength > MAX_API_KEY_LENGTH) {
            log.warn("API Key 长度非法, length={}, uri={}", headerValueLength, request.getRequestURI());
            authenticationErrorWriter.writeUnauthorized(response, "API Key 无效或已失效");
            return;
        }
        // 步骤4：摘要后回查账号，逻辑删除的用户被 MyBatis-Plus 自动过滤，等同于密钥失效
        String apiKeyDigest = ApiKeyCodec.digest(apiKey);
        User agentUser = findUserByDigest(apiKeyDigest);
        if (agentUser == null) {
            log.warn("API Key 未匹配到账号, uri={}", request.getRequestURI());
            authenticationErrorWriter.writeUnauthorized(response, "API Key 无效或已失效");
            return;
        }
        // 步骤4.1：账号被封禁时密钥立即失效——封禁作用于整个账号，网页端与机器端一起停
        if (UserStatus.isBanned(agentUser.getStatus())) {
            log.warn("API Key 对应账号已被封禁, userId={}, uri={}", agentUser.getId(), request.getRequestURI());
            authenticationErrorWriter.writeUnauthorized(response, "账号已被封禁，请联系管理员");
            return;
        }
        // 步骤5：写入认证上下文，agent 标记为 true，业务层据此走代发逻辑；API Key 没有 jti，tokenId 传 null
        LoginUser loginUser = new LoginUser(agentUser.getId(), agentUser.getUsername(), true, null);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                loginUser, null, List.of(new SimpleGrantedAuthority(AGENT_ROLE)));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        log.info("Agent API Key 认证通过, userId={}, uri={}", agentUser.getId(), request.getRequestURI());
        // 步骤6：继续执行后续过滤器
        filterChain.doFilter(request, response);
    }

    /**
     * 按密钥摘要查询账号。
     *
     * <p>用 selectList 取一条而非 selectOne：摘要理论上唯一，但若历史数据存在重复，
     * selectOne 会抛异常导致 500，这里取第一条并告警更稳妥。</p>
     *
     * @param apiKeyDigest 密钥摘要
     * @return 命中的用户；未命中返回 null
     */
    private User findUserByDigest(String apiKeyDigest) {
        // 步骤1：等值查询 api_key 列，命中 idx_api_key 索引
        LambdaQueryWrapper<User> userQuery = new LambdaQueryWrapper<User>()
                .eq(User::getApiKey, apiKeyDigest);
        List<User> matchedUserList = userMapper.selectList(userQuery);
        // 步骤2：重复摘要属于数据异常，记录告警但不阻断认证（未排序，取查询结果第一条）
        if (matchedUserList.size() > 1) {
            log.warn("检测到多个账号使用相同 API Key 摘要, 请排查并重新签发, count={}", matchedUserList.size());
        }
        return matchedUserList.isEmpty() ? null : matchedUserList.get(0);
    }
}
