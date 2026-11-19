package com.linnea.lingxi.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TokenStore} 单元测试：覆盖刷新令牌登记/轮换/吊销与访问令牌吊销标记，
 * 重点验证两条失败策略——刷新按 fail-closed 拒绝、吊销查询按 fail-open 放行。
 */
@ExtendWith(MockitoExtension.class)
class TokenStoreTest {

    /** 测试用刷新令牌 jti。 */
    private static final String REFRESH_TOKEN_ID = "refresh-jti-0001";

    /** 测试用访问令牌 jti。 */
    private static final String ACCESS_TOKEN_ID = "access-jti-0001";

    /** 测试用户ID。 */
    private static final Long USER_ID = 1001L;

    /** 另一个用户ID，用于构造归属不符的场景。 */
    private static final Long OTHER_USER_ID = 2002L;

    /** 刷新令牌有效期 7 天（毫秒）。 */
    private static final long REFRESH_TTL = 604_800_000L;

    /** 吊销标记保留时长 2 小时（毫秒）。 */
    private static final long REVOKED_RETENTION = 7_200_000L;

    /** 刷新令牌登记键，测试里需要精确断言键名。 */
    private static final String REFRESH_KEY = "lingxi:token:refresh:" + REFRESH_TOKEN_ID;

    /** 访问令牌吊销键，测试里需要精确断言键名。 */
    private static final String REVOKED_KEY = "lingxi:token:revoked:access:" + ACCESS_TOKEN_ID;

    /** Redis 操作模板替身。 */
    @Mock
    private StringRedisTemplate stringRedisTemplate;

    /** 值操作替身。 */
    @Mock
    private ValueOperations<String, String> valueOperations;

    /** 被测对象：构造参数含基本类型保留时长，故在 setUp 中显式构造。 */
    private TokenStore tokenStore;

    /**
     * 每个用例前重建被测对象，保证配置值与替身都是新的。
     */
    @BeforeEach
    void setUp() {
        tokenStore = new TokenStore(stringRedisTemplate, REVOKED_RETENTION);
    }

    @Test
    @DisplayName("刷新令牌登记：键为 jti、值为用户ID、TTL 与令牌同寿")
    void registerRefreshToken_writesKeyWithTtl() {
        // 前置
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        // 执行
        tokenStore.registerRefreshToken(REFRESH_TOKEN_ID, USER_ID, REFRESH_TTL);

        // 断言：TTL 必须跟着刷新令牌走，否则键会永久堆积或过早消失
        verify(valueOperations).set(REFRESH_KEY, String.valueOf(USER_ID), Duration.ofMillis(REFRESH_TTL));
    }

    @Test
    @DisplayName("刷新令牌登记失败：只告警不抛错，登录不能因为 Redis 抖动而整体失败")
    void registerRefreshToken_redisFailure_doesNotThrow() {
        // 前置
        when(stringRedisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("模拟 Redis 不可用"));

        // 执行 + 断言
        assertThatCode(() -> tokenStore.registerRefreshToken(REFRESH_TOKEN_ID, USER_ID, REFRESH_TTL))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("刷新令牌消费：登记命中且归属一致返回 true，并原子取走（GETDEL）")
    void consumeRefreshToken_matched_returnsTrue() {
        // 前置
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(REFRESH_KEY)).thenReturn(String.valueOf(USER_ID));

        // 执行
        boolean consumed = tokenStore.consumeRefreshToken(REFRESH_TOKEN_ID, USER_ID);

        // 断言
        assertThat(consumed).isTrue();
        verify(valueOperations).getAndDelete(REFRESH_KEY);
    }

    @Test
    @DisplayName("刷新令牌归属不符：拒绝换发，防止拿他人令牌换出自己的新令牌")
    void consumeRefreshToken_userMismatch_returnsFalse() {
        // 前置
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(REFRESH_KEY)).thenReturn(String.valueOf(OTHER_USER_ID));

        // 执行 + 断言
        assertThat(tokenStore.consumeRefreshToken(REFRESH_TOKEN_ID, USER_ID)).isFalse();
    }

    @Test
    @DisplayName("刷新令牌未登记（已用过/已登出/伪造）：返回 false")
    void consumeRefreshToken_notRegistered_returnsFalse() {
        // 前置
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(REFRESH_KEY)).thenReturn(null);

        // 执行 + 断言
        assertThat(tokenStore.consumeRefreshToken(REFRESH_TOKEN_ID, USER_ID)).isFalse();
    }

    @Test
    @DisplayName("刷新令牌消费遇 Redis 异常：fail-closed 返回 false，不能无凭据换发")
    void consumeRefreshToken_redisFailure_returnsFalse() {
        // 前置
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(anyString())).thenThrow(new RedisConnectionFailureException("模拟 Redis 不可用"));

        // 执行 + 断言
        assertThat(tokenStore.consumeRefreshToken(REFRESH_TOKEN_ID, USER_ID)).isFalse();
    }

    @Test
    @DisplayName("登出吊销刷新令牌：直接删除登记记录")
    void revokeRefreshToken_deletesKey() {
        // 前置

        // 执行
        tokenStore.revokeRefreshToken(REFRESH_TOKEN_ID);

        // 断言
        verify(stringRedisTemplate).delete(REFRESH_KEY);
    }

    @Test
    @DisplayName("登出吊销访问令牌：写入带保留时长的标记键，时长取配置项")
    void revokeAccessToken_writesMarkerWithConfiguredRetention() {
        // 前置
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        // 执行
        tokenStore.revokeAccessToken(ACCESS_TOKEN_ID);

        // 断言：保留时长必须覆盖访问令牌剩余有效期，否则标记先过期、令牌又能用
        verify(valueOperations).set(REVOKED_KEY, "1", Duration.ofMillis(REVOKED_RETENTION));
    }

    @Test
    @DisplayName("吊销标记查询：键存在即已吊销")
    void isAccessTokenRevoked_keyExists_returnsTrue() {
        // 前置
        when(stringRedisTemplate.hasKey(REVOKED_KEY)).thenReturn(Boolean.TRUE);

        // 执行 + 断言
        assertThat(tokenStore.isAccessTokenRevoked(ACCESS_TOKEN_ID)).isTrue();
    }

    @Test
    @DisplayName("吊销标记查询：无标记即未吊销")
    void isAccessTokenRevoked_keyMissing_returnsFalse() {
        // 前置
        when(stringRedisTemplate.hasKey(REVOKED_KEY)).thenReturn(Boolean.FALSE);

        // 执行 + 断言
        assertThat(tokenStore.isAccessTokenRevoked(ACCESS_TOKEN_ID)).isFalse();
    }

    @Test
    @DisplayName("吊销标记查询遇 Redis 异常：fail-open 放行，避免全站 401")
    void isAccessTokenRevoked_redisFailure_returnsFalse() {
        // 前置
        when(stringRedisTemplate.hasKey(REVOKED_KEY)).thenThrow(new RedisConnectionFailureException("模拟 Redis 不可用"));

        // 执行 + 断言
        assertThat(tokenStore.isAccessTokenRevoked(ACCESS_TOKEN_ID)).isFalse();
    }
}
