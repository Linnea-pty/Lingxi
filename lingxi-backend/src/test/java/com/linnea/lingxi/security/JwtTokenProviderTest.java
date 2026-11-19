package com.linnea.lingxi.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JwtTokenProvider} 单元测试：用真实签名算法验证令牌类型隔离、jti 唯一性与密钥强度校验。
 *
 * <p>WHY 不用 Mockito 替身：这段代码的价值全在「真的签、真的验」，把签名器替掉等于只测了个 if。</p>
 */
class JwtTokenProviderTest {

    /** 测试专用密钥（Base64，33 字节），与 application-test.properties 中的值一致。 */
    private static final String TEST_SECRET = "bGluZ3hpLXRlc3Qtb25seS1qd3Qtc2VjcmV0LTMyYnl0ZXM=";

    /** 强度不足的密钥（Base64，不足 32 字节）。 */
    private static final String WEAK_SECRET = "c2hvcnQ=";

    /** 访问令牌有效期 2 小时。 */
    private static final long ACCESS_EXPIRATION = 7_200_000L;

    /** 刷新令牌有效期 7 天。 */
    private static final long REFRESH_EXPIRATION = 604_800_000L;

    /** 测试用户ID。 */
    private static final Long USER_ID = 1001L;

    /** 测试用户名。 */
    private static final String USERNAME = "linxi";

    /** 被测对象。 */
    private final JwtTokenProvider jwtTokenProvider =
            new JwtTokenProvider(TEST_SECRET, ACCESS_EXPIRATION, REFRESH_EXPIRATION);

    @Test
    @DisplayName("访问令牌：subject 为用户ID、type=access、带 jti 与用户名声明")
    void generateAccessToken_containsExpectedClaims() {
        // 执行
        IssuedToken issuedToken = jwtTokenProvider.generateAccessToken(USER_ID, USERNAME);
        Claims claims = jwtTokenProvider.parseAccessToken(issuedToken.tokenValue());

        // 断言
        assertThat(claims.getSubject()).isEqualTo(String.valueOf(USER_ID));
        assertThat(claims.get(JwtTokenProvider.TOKEN_TYPE_CLAIM, String.class))
                .isEqualTo(JwtTokenProvider.ACCESS_TOKEN_TYPE);
        assertThat(claims.get("username", String.class)).isEqualTo(USERNAME);
        assertThat(claims.getId()).isEqualTo(issuedToken.tokenId());
        assertThat(issuedToken.expiresInMillis()).isEqualTo(ACCESS_EXPIRATION);
    }

    @Test
    @DisplayName("刷新令牌：type=refresh 且有效期取配置值，与访问令牌互不通用")
    void generateRefreshToken_isNotAcceptedAsAccessToken() {
        // 执行
        IssuedToken issuedToken = jwtTokenProvider.generateRefreshToken(USER_ID, USERNAME);

        // 断言：刷新令牌能按 refresh 解析，但不能当访问令牌用（否则它能直接调接口，等于长寿命访问令牌）
        assertThat(jwtTokenProvider.parseRefreshToken(issuedToken.tokenValue()).getId())
                .isEqualTo(issuedToken.tokenId());
        assertThat(issuedToken.expiresInMillis()).isEqualTo(REFRESH_EXPIRATION);
        assertThatThrownBy(() -> jwtTokenProvider.parseAccessToken(issuedToken.tokenValue()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("访问令牌不能当刷新令牌换发，类型声明就是这条边界")
    void accessToken_isNotAcceptedAsRefreshToken() {
        // 前置
        IssuedToken issuedToken = jwtTokenProvider.generateAccessToken(USER_ID, USERNAME);

        // 执行 + 断言
        assertThatThrownBy(() -> jwtTokenProvider.parseRefreshToken(issuedToken.tokenValue()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("每次签发 jti 都不同：吊销与轮换都靠它定位单条令牌")
    void generateAccessToken_producesUniqueTokenId() {
        // 执行
        IssuedToken firstToken = jwtTokenProvider.generateAccessToken(USER_ID, USERNAME);
        IssuedToken secondToken = jwtTokenProvider.generateAccessToken(USER_ID, USERNAME);

        // 断言
        assertThat(firstToken.tokenId()).isNotEqualTo(secondToken.tokenId());
        assertThat(firstToken.tokenValue()).isNotEqualTo(secondToken.tokenValue());
    }

    @Test
    @DisplayName("已过期的访问令牌：抛 ExpiredJwtException，由过滤器转 401 提示重新登录")
    void parseAccessToken_expiredToken_throwsExpiredJwtException() {
        // 前置：有效期设为负数，签发即过期，避免测试里真的等待
        JwtTokenProvider expiredProvider = new JwtTokenProvider(TEST_SECRET, -1_000L, REFRESH_EXPIRATION);
        IssuedToken expiredToken = expiredProvider.generateAccessToken(USER_ID, USERNAME);

        // 执行 + 断言
        assertThatThrownBy(() -> expiredProvider.parseAccessToken(expiredToken.tokenValue()))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("伪造令牌：用另一把密钥签的令牌必须验签失败")
    void parseAccessToken_foreignSignature_throwsJwtException() {
        // 前置：另一把合法但不同的密钥
        JwtTokenProvider otherProvider = new JwtTokenProvider(
                "b3RoZXItc2VjcmV0LWZvci1saW5neGktdGVzdHMtMTIzNDU2Nzg=", ACCESS_EXPIRATION, REFRESH_EXPIRATION);
        String foreignToken = otherProvider.generateAccessToken(USER_ID, USERNAME).tokenValue();

        // 执行 + 断言
        assertThatThrownBy(() -> jwtTokenProvider.parseAccessToken(foreignToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("弱密钥：构造即抛错，不让强度不足的密钥悄悄上线")
    void constructor_weakSecret_throwsIllegalState() {
        // 执行 + 断言
        assertThatThrownBy(() -> new JwtTokenProvider(WEAK_SECRET, ACCESS_EXPIRATION, REFRESH_EXPIRATION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("256 bit");
    }
}
