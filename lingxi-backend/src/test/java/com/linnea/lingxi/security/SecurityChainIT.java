package com.linnea.lingxi.security;

import com.linnea.lingxi.entity.User;
import com.linnea.lingxi.mapper.UserMapper;
import com.linnea.lingxi.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全链路集成测试：验证过滤器对「无令牌 / 伪造令牌 / 类型混用 / 已吊销」的实际响应。
 *
 * <p>WHY 用 MockMvc 走真过滤器链：JWT 过滤器的成败在链上，直接调 Controller 或 Service
 * 都会绕过鉴权；这里与线上同一套 SecurityFilterChain，401 的响应体也一并校验，
 * 保证异常没有逸出成 500、也没有绕开统一返回体。</p>
 */
@Transactional
@AutoConfigureMockMvc
class SecurityChainIT extends AbstractIntegrationTest {

    /** 受保护接口，用于观察鉴权结果。 */
    private static final String PROTECTED_URI = "/api/v1/auth/current-user-info";

    /** 走真实过滤器链的入口，行为与线上一致。 */
    @Autowired
    private MockMvc mockMvc;

    /** 用户数据访问：直接造一个已认证账号，避免依赖注册接口。 */
    @Autowired
    private UserMapper userMapper;

    /** 令牌签发组件：造合法令牌与刷新令牌。 */
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    /** 令牌状态存储：造吊销标记。 */
    @Autowired
    private TokenStore tokenStore;

    /** 测试账号ID。 */
    private Long userId;

    /** 测试账号用户名。 */
    private String username;

    /**
     * 每个用例前插入一个可用账号。
     */
    @BeforeEach
    void setUpUser() {
        // 步骤1：插入账号（事务随测试回滚，不影响其他用例）
        User user = new User();
        user.setUsername("sec_" + System.nanoTime());
        user.setPasswordHash("$2a$10$placeholder-placeholder-placeholder");
        user.setNickname("安全测试");
        userMapper.insert(user);
        // 步骤2：保存ID与用户名供签发令牌使用
        this.userId = user.getId();
        this.username = user.getUsername();
    }

    @Test
    @DisplayName("无令牌访问受保护接口：401，且响应体仍是统一结构")
    void noToken_returns401() throws Exception {
        // 执行 + 断言
        mockMvc.perform(MockMvcRequestBuilders.get(PROTECTED_URI))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("伪造令牌：401，不能让任何非法签名进入业务层")
    void invalidToken_returns401() throws Exception {
        // 执行 + 断言
        mockMvc.perform(MockMvcRequestBuilders.get(PROTECTED_URI)
                        .header(AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("刷新令牌当访问令牌：401，类型声明拦住混用")
    void refreshTokenAsAccessToken_returns401() throws Exception {
        // 前置
        String refreshToken = jwtTokenProvider.generateRefreshToken(userId, username).tokenValue();

        // 执行 + 断言
        mockMvc.perform(MockMvcRequestBuilders.get(PROTECTED_URI)
                        .header(AUTHORIZATION, "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("合法访问令牌：200 且返回当前用户信息")
    void validAccessToken_returns200() throws Exception {
        // 前置
        String accessToken = jwtTokenProvider.generateAccessToken(userId, username).tokenValue();

        // 执行 + 断言
        mockMvc.perform(MockMvcRequestBuilders.get(PROTECTED_URI)
                        .header(AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.userId").value(userId));
    }

    @Test
    @DisplayName("已吊销的访问令牌：401，登出后旧令牌在有效期内立刻失效")
    void revokedAccessToken_returns401() throws Exception {
        // 前置：签发后立即写吊销标记，模拟登出
        IssuedToken issuedToken = jwtTokenProvider.generateAccessToken(userId, username);
        tokenStore.revokeAccessToken(issuedToken.tokenId());

        // 执行 + 断言
        mockMvc.perform(MockMvcRequestBuilders.get(PROTECTED_URI)
                        .header(AUTHORIZATION, "Bearer " + issuedToken.tokenValue()))
                .andExpect(status().isUnauthorized());
    }
}
