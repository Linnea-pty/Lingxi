package com.linnea.lingxi.service.impl;

import com.linnea.lingxi.common.exception.BusinessException;
import com.linnea.lingxi.common.exception.ErrorCode;
import com.linnea.lingxi.entity.User;
import com.linnea.lingxi.mapper.UserMapper;
import com.linnea.lingxi.security.IssuedToken;
import com.linnea.lingxi.security.JwtTokenProvider;
import com.linnea.lingxi.security.TokenStore;
import com.linnea.lingxi.vo.LoginVO;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AuthServiceImpl} 令牌相关分支的单元测试：换发（轮换 + 重放拒绝）与登出（双吊销）。
 *
 * <p>只验证业务分支与调用顺序；Redis 的原子性、MySQL 的唯一键等真实行为由集成测试覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    /** 测试用户ID。 */
    private static final Long USER_ID = 1001L;

    /** 测试用户名。 */
    private static final String USERNAME = "linxi";

    /** 客户端提交的刷新令牌原文（测试用占位串，不参与真实签名）。 */
    private static final String RAW_REFRESH_TOKEN = "raw-refresh-token";

    /** 旧刷新令牌 jti。 */
    private static final String OLD_REFRESH_TOKEN_ID = "old-refresh-jti";

    /** 新访问令牌 jti。 */
    private static final String NEW_ACCESS_TOKEN_ID = "new-access-jti";

    /** 新刷新令牌 jti。 */
    private static final String NEW_REFRESH_TOKEN_ID = "new-refresh-jti";

    /** 当前访问令牌 jti，登出时用于写吊销标记。 */
    private static final String CURRENT_ACCESS_TOKEN_ID = "current-access-jti";

    /** 访问令牌有效期 2 小时（毫秒）。 */
    private static final long ACCESS_EXPIRATION = 7_200_000L;

    /** 刷新令牌有效期 7 天（毫秒）。 */
    private static final long REFRESH_EXPIRATION = 604_800_000L;

    /** 用户数据访问替身。 */
    @Mock
    private UserMapper userMapper;

    /** 密码编码器替身。 */
    @Mock
    private PasswordEncoder passwordEncoder;

    /** 令牌签发组件替身。 */
    @Mock
    private JwtTokenProvider jwtTokenProvider;

    /** 令牌状态存储替身。 */
    @Mock
    private TokenStore tokenStore;

    /** 被测对象。 */
    @InjectMocks
    private AuthServiceImpl authService;

    @Test
    @DisplayName("换发令牌：刷新令牌为空时按无效拒绝，不进入解析")
    void refreshToken_blank_throwsInvalid() {
        // 执行 + 断言
        assertThatThrownBy(() -> authService.refreshToken("   "))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.AUTH_REFRESH_TOKEN_INVALID.getCode());
        verifyNoInteractions(jwtTokenProvider);
    }

    @Test
    @DisplayName("换发令牌：签名非法（例如有人拿访问令牌来换发）按无效拒绝")
    void refreshToken_parseFails_throwsInvalid() {
        // 前置
        when(jwtTokenProvider.parseRefreshToken(RAW_REFRESH_TOKEN)).thenThrow(new JwtException("令牌类型不是 refresh"));

        // 执行 + 断言
        assertThatThrownBy(() -> authService.refreshToken(RAW_REFRESH_TOKEN))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.AUTH_REFRESH_TOKEN_INVALID.getCode());
        verifyNoInteractions(tokenStore);
    }

    @Test
    @DisplayName("换发令牌：刷新令牌已过期时给出「重新登录」文案，前端据此清令牌")
    void refreshToken_expired_throwsInvalidWithMessage() {
        // 前置
        when(jwtTokenProvider.parseRefreshToken(RAW_REFRESH_TOKEN))
                .thenThrow(new ExpiredJwtException(null, null, "已过期"));

        // 执行 + 断言
        assertThatThrownBy(() -> authService.refreshToken(RAW_REFRESH_TOKEN))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已过期");
    }

    @Test
    @DisplayName("换发令牌：旧刷新令牌已被消费（重放）时拒绝，且不签发任何新令牌")
    void refreshToken_replayed_throwsInvalid() {
        // 前置：解析通过，但登记记录已被取走
        Claims claims = buildClaims(USER_ID, OLD_REFRESH_TOKEN_ID);
        when(jwtTokenProvider.parseRefreshToken(RAW_REFRESH_TOKEN)).thenReturn(claims);
        when(tokenStore.consumeRefreshToken(OLD_REFRESH_TOKEN_ID, USER_ID)).thenReturn(false);

        // 执行 + 断言
        assertThatThrownBy(() -> authService.refreshToken(RAW_REFRESH_TOKEN))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.AUTH_REFRESH_TOKEN_INVALID.getCode());
        verify(jwtTokenProvider, never()).generateAccessToken(any(), anyString());
    }

    @Test
    @DisplayName("换发令牌：账号已注销时按用户不存在拒绝，旧令牌不再续命")
    void refreshToken_userMissing_throwsUserNotFound() {
        // 前置
        Claims claims = buildClaims(USER_ID, OLD_REFRESH_TOKEN_ID);
        when(jwtTokenProvider.parseRefreshToken(RAW_REFRESH_TOKEN)).thenReturn(claims);
        when(tokenStore.consumeRefreshToken(OLD_REFRESH_TOKEN_ID, USER_ID)).thenReturn(true);
        when(userMapper.selectById(USER_ID)).thenReturn(null);

        // 执行 + 断言
        assertThatThrownBy(() -> authService.refreshToken(RAW_REFRESH_TOKEN))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.USER_NOT_FOUND.getCode());
        verify(jwtTokenProvider, never()).generateAccessToken(any(), anyString());
    }

    @Test
    @DisplayName("换发令牌成功：返回新一套令牌，并登记新刷新令牌（旧的同时作废）")
    void refreshToken_success_issuesNewPairAndRegisters() {
        // 前置
        Claims claims = buildClaims(USER_ID, OLD_REFRESH_TOKEN_ID);
        when(jwtTokenProvider.parseRefreshToken(RAW_REFRESH_TOKEN)).thenReturn(claims);
        when(tokenStore.consumeRefreshToken(OLD_REFRESH_TOKEN_ID, USER_ID)).thenReturn(true);
        when(userMapper.selectById(USER_ID)).thenReturn(buildUser());
        when(jwtTokenProvider.generateAccessToken(USER_ID, USERNAME))
                .thenReturn(new IssuedToken("new-access-token", NEW_ACCESS_TOKEN_ID, ACCESS_EXPIRATION));
        when(jwtTokenProvider.generateRefreshToken(USER_ID, USERNAME))
                .thenReturn(new IssuedToken("new-refresh-token", NEW_REFRESH_TOKEN_ID, REFRESH_EXPIRATION));

        // 执行
        LoginVO loginResult = authService.refreshToken(RAW_REFRESH_TOKEN);

        // 断言：响应换成新令牌，且新刷新令牌已登记（否则下一次换发必然失败）
        assertThat(loginResult.getAccessToken()).isEqualTo("new-access-token");
        assertThat(loginResult.getRefreshToken()).isEqualTo("new-refresh-token");
        assertThat(loginResult.getExpiresIn()).isEqualTo(ACCESS_EXPIRATION);
        assertThat(loginResult.getRefreshExpiresIn()).isEqualTo(REFRESH_EXPIRATION);
        assertThat(loginResult.getUserInfo().getUserId()).isEqualTo(USER_ID);
        verify(tokenStore).registerRefreshToken(NEW_REFRESH_TOKEN_ID, USER_ID, REFRESH_EXPIRATION);
    }

    @Test
    @DisplayName("登出：缺少访问令牌 jti（例如 API Key 认证）时拒绝，无令牌可吊销")
    void logout_missingTokenId_throwsUnauthorized() {
        // 执行 + 断言
        assertThatThrownBy(() -> authService.logout(USER_ID, null, RAW_REFRESH_TOKEN))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.COMMON_UNAUTHORIZED.getCode());
        verifyNoInteractions(tokenStore);
    }

    @Test
    @DisplayName("登出：访问令牌与刷新令牌一并吊销")
    void logout_revokesBothTokens() {
        // 前置：刷新令牌可解析，说明客户端提交的是有效令牌
        Claims claims = buildClaims(USER_ID, OLD_REFRESH_TOKEN_ID);
        when(jwtTokenProvider.parseRefreshToken(RAW_REFRESH_TOKEN)).thenReturn(claims);

        // 执行
        authService.logout(USER_ID, CURRENT_ACCESS_TOKEN_ID, RAW_REFRESH_TOKEN);

        // 断言
        verify(tokenStore).revokeAccessToken(CURRENT_ACCESS_TOKEN_ID);
        verify(tokenStore).revokeRefreshToken(OLD_REFRESH_TOKEN_ID);
    }

    @Test
    @DisplayName("登出：刷新令牌无效时只吊销访问令牌，登出本身不能失败")
    void logout_invalidRefreshToken_stillRevokesAccessToken() {
        // 前置
        when(jwtTokenProvider.parseRefreshToken(anyString())).thenThrow(new JwtException("令牌非法"));

        // 执行
        authService.logout(USER_ID, CURRENT_ACCESS_TOKEN_ID, RAW_REFRESH_TOKEN);

        // 断言：访问令牌已吊销，刷新令牌不处理也不报错
        verify(tokenStore).revokeAccessToken(CURRENT_ACCESS_TOKEN_ID);
        verify(tokenStore, never()).revokeRefreshToken(anyString());
    }

    @Test
    @DisplayName("登出：未提交刷新令牌时只吊销访问令牌")
    void logout_withoutRefreshToken_revokesAccessTokenOnly() {
        // 执行
        authService.logout(USER_ID, CURRENT_ACCESS_TOKEN_ID, null);

        // 断言
        verify(tokenStore).revokeAccessToken(CURRENT_ACCESS_TOKEN_ID);
        verifyNoInteractions(jwtTokenProvider);
    }

    /**
     * 构造令牌声明替身。
     *
     * @param userId  用户ID，写入 subject
     * @param tokenId 令牌 jti
     * @return 令牌声明替身
     */
    private Claims buildClaims(Long userId, String tokenId) {
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        // 用 lenient：登出路径只读 jti、不读 subject，严格校验会把「没用到」当成错误
        org.mockito.Mockito.lenient().when(claims.getSubject()).thenReturn(String.valueOf(userId));
        org.mockito.Mockito.lenient().when(claims.getId()).thenReturn(tokenId);
        return claims;
    }

    /**
     * 构造用户实体。
     *
     * @return 用户实体
     */
    private User buildUser() {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername(USERNAME);
        user.setNickname("灵犀");
        user.setAvatarUrl("");
        return user;
    }
}
