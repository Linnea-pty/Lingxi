package com.linnea.lingxi.service.impl;

import com.linnea.lingxi.common.exception.BusinessException;
import com.linnea.lingxi.common.exception.ErrorCode;
import com.linnea.lingxi.dto.RegisterDTO;
import com.linnea.lingxi.security.JwtTokenProvider;
import com.linnea.lingxi.security.TokenStore;
import com.linnea.lingxi.service.AuthService;
import com.linnea.lingxi.support.AbstractIntegrationTest;
import com.linnea.lingxi.vo.LoginVO;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 令牌全链路集成测试：注册 → 换发（轮换）→ 重放 → 登出（双吊销），跑在真实 MySQL 与真实 Redis 上。
 *
 * <p>WHY 放集成层：轮换与吊销的正确性依赖 Redis 的 GETDEL 原子语义与真实 TTL，
 * 单测里用替身只能验证「调用发生了」，验证不了「并发/重放时到底谁赢」。</p>
 */
@Transactional
class AuthTokenFlowIT extends AbstractIntegrationTest {

    /** 测试账号密码，满足 8~64 位要求。 */
    private static final String PASSWORD = "lingxi@2026";

    /** 认证业务服务。 */
    @Autowired
    private AuthService authService;

    /** 令牌签发组件：用于从返回值里取出 jti 做断言。 */
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    /** 令牌状态存储：直接查询吊销标记，确认服务端状态真的写了。 */
    @Autowired
    private TokenStore tokenStore;

    @Test
    @DisplayName("换发令牌：签发新一套令牌，旧刷新令牌立刻失效（重放被拒）")
    void refreshToken_rotatesAndRejectsReplay() {
        // 前置：注册即登录，拿到首套令牌
        LoginVO firstLogin = authService.register(buildRegisterInfo("refresh_alice"));
        assertThat(firstLogin.getRefreshToken()).isNotBlank();

        // 步骤1：用刷新令牌换发新一套令牌
        LoginVO refreshedLogin = authService.refreshToken(firstLogin.getRefreshToken());

        // 步骤2：新令牌可用且与旧令牌不同
        Claims newRefreshClaims = jwtTokenProvider.parseRefreshToken(refreshedLogin.getRefreshToken());
        assertThat(refreshedLogin.getAccessToken()).isNotEqualTo(firstLogin.getAccessToken());
        assertThat(newRefreshClaims.getSubject()).isEqualTo(String.valueOf(refreshedLogin.getUserInfo().getUserId()));

        // 步骤3：重放旧刷新令牌必须被拒（旧 jti 已被 GETDEL 取走）
        assertThatThrownBy(() -> authService.refreshToken(firstLogin.getRefreshToken()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.AUTH_REFRESH_TOKEN_INVALID.getCode());
    }

    @Test
    @DisplayName("换发令牌：刷新令牌不能当访问令牌用，type 声明拦住混用")
    void refreshToken_cannotBeUsedAsAccessToken() {
        // 前置
        LoginVO loginResult = authService.register(buildRegisterInfo("refresh_bob"));

        // 执行 + 断言
        assertThatThrownBy(() -> jwtTokenProvider.parseAccessToken(loginResult.getRefreshToken()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("登出：访问令牌写入吊销标记，刷新令牌被删除后无法再换发")
    void logout_revokesAccessAndRefreshToken() {
        // 前置
        LoginVO loginResult = authService.register(buildRegisterInfo("logout_carol"));
        String accessTokenId = jwtTokenProvider.parseAccessToken(loginResult.getAccessToken()).getId();

        // 步骤1：登出并同时提交刷新令牌
        authService.logout(loginResult.getUserInfo().getUserId(), accessTokenId, loginResult.getRefreshToken());

        // 步骤2：访问令牌的吊销标记确实存在于 Redis
        assertThat(tokenStore.isAccessTokenRevoked(accessTokenId)).isTrue();

        // 步骤3：刷新令牌登记已删除，再换发按无效拒绝
        assertThatThrownBy(() -> authService.refreshToken(loginResult.getRefreshToken()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.AUTH_REFRESH_TOKEN_INVALID.getCode());
    }

    @Test
    @DisplayName("登出可重复调用：第二次仍成功，标记是覆盖写而不是累加")
    void logout_isIdempotent() {
        // 前置
        LoginVO loginResult = authService.register(buildRegisterInfo("logout_dave"));
        String accessTokenId = jwtTokenProvider.parseAccessToken(loginResult.getAccessToken()).getId();

        // 执行：连续登出两次
        authService.logout(loginResult.getUserInfo().getUserId(), accessTokenId, loginResult.getRefreshToken());
        authService.logout(loginResult.getUserInfo().getUserId(), accessTokenId, null);

        // 断言
        assertThat(tokenStore.isAccessTokenRevoked(accessTokenId)).isTrue();
    }

    /**
     * 构造注册参数。
     *
     * @param username 用户名，需符合 4~32 位字母/数字/下划线
     * @return 注册参数
     */
    private RegisterDTO buildRegisterInfo(String username) {
        RegisterDTO registerInfo = new RegisterDTO();
        registerInfo.setUsername(username);
        registerInfo.setPassword(PASSWORD);
        registerInfo.setNickname(username);
        return registerInfo;
    }
}
