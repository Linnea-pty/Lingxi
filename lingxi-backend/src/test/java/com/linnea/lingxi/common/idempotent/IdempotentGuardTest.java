package com.linnea.lingxi.common.idempotent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link IdempotentGuard} 单元测试：覆盖抢占、回填、查询、释放与 Redis 故障降级。
 *
 * <p>被测对象的依赖只有 Redis 模板，全部用 Mockito 替身，不连真实 Redis。</p>
 */
@ExtendWith(MockitoExtension.class)
class IdempotentGuardTest {

    /** 测试用场景标识。 */
    private static final String SCENE = "post:publish";

    /** 测试用幂等键。 */
    private static final String REQUEST_ID = "req-0001";

    /** 测试用用户ID。 */
    private static final Long USER_ID = 1001L;

    /** 拼接后的 Redis 键，与 IdempotentGuard#buildKey 的格式一致。 */
    private static final String EXPECTED_KEY = "lingxi:idempotent:" + SCENE + ":" + USER_ID + ":" + REQUEST_ID;

    /** Redis 字符串模板替身。 */
    @Mock
    private StringRedisTemplate stringRedisTemplate;

    /** Redis 值操作替身。 */
    @Mock
    private ValueOperations<String, String> valueOperations;

    /** 被测对象。 */
    @InjectMocks
    private IdempotentGuard idempotentGuard;

    @AfterEach
    void tearDown() {
        // 无共享状态需要清理，保留钩子以便后续扩展
    }

    @Test
    @DisplayName("首次抢占：SET NX 成功时返回 true，并以 30 分钟 TTL 写入处理中占位值")
    void tryAcquire_firstRequest_returnsTrue() {
        // 前置：SET NX 成功
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(EXPECTED_KEY), anyString(), any(Duration.class))).thenReturn(Boolean.TRUE);

        // 执行
        boolean firstRequest = idempotentGuard.tryAcquire(SCENE, REQUEST_ID, USER_ID);

        // 断言：返回首次，且写入的 TTL 为 30 分钟（覆盖一次请求的最长处理时间）
        assertThat(firstRequest).isTrue();
        verify(valueOperations).setIfAbsent(EXPECTED_KEY, "PROCESSING", Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("重复抢占：SET NX 返回 false 时判定为重复提交")
    void tryAcquire_conflict_returnsFalse() {
        // 前置：键已存在
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(EXPECTED_KEY), anyString(), any(Duration.class))).thenReturn(Boolean.FALSE);

        // 执行
        boolean firstRequest = idempotentGuard.tryAcquire(SCENE, REQUEST_ID, USER_ID);

        // 断言
        assertThat(firstRequest).isFalse();
    }

    @Test
    @DisplayName("Redis 故障：fail-open 放行，不让缓存故障阻断发布")
    void tryAcquire_redisFailure_failOpenReturnsTrue() {
        // 前置：Redis 连接失败
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        // 执行
        boolean firstRequest = idempotentGuard.tryAcquire(SCENE, REQUEST_ID, USER_ID);

        // 断言：降级放行，保证功能可用（代价是此窗口内可能重复落库）
        assertThat(firstRequest).isTrue();
    }

    @Test
    @DisplayName("回填结果：写入业务结果并重置 TTL")
    void bindResult_writesResultValue() {
        // 前置
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        // 执行
        idempotentGuard.bindResult(SCENE, REQUEST_ID, USER_ID, "9527");

        // 断言：结果值覆盖占位符，TTL 与抢占时一致
        verify(valueOperations).set(EXPECTED_KEY, "9527", Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("回填失败：异常不外抛，避免影响已成功的业务")
    void bindResult_redisFailure_doesNotThrow() {
        // 前置：写入抛异常
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("redis down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        // 执行 + 断言：不抛异常
        idempotentGuard.bindResult(SCENE, REQUEST_ID, USER_ID, "9527");
        verify(valueOperations).set(EXPECTED_KEY, "9527", Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("查询结果：命中返回键值，未命中返回 null")
    void queryResult_hitAndMiss() {
        // 前置
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(EXPECTED_KEY)).thenReturn("9527");

        // 执行 + 断言
        assertThat(idempotentGuard.queryResult(SCENE, REQUEST_ID, USER_ID)).isEqualTo("9527");

        // 再给定未命中
        when(valueOperations.get(EXPECTED_KEY)).thenReturn(null);
        assertThat(idempotentGuard.queryResult(SCENE, REQUEST_ID, USER_ID)).isNull();
    }

    @Test
    @DisplayName("查询失败：按未命中处理，让调用方转成重复提交拒绝而不是重复写库")
    void queryResult_redisFailure_returnsNull() {
        // 前置
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(EXPECTED_KEY)).thenThrow(new RedisConnectionFailureException("redis down"));

        // 执行 + 断言
        assertThat(idempotentGuard.queryResult(SCENE, REQUEST_ID, USER_ID)).isNull();
    }

    @Test
    @DisplayName("释放幂等键：删除对应 Redis 键")
    void release_deletesKey() {
        // 执行
        idempotentGuard.release(SCENE, REQUEST_ID, USER_ID);

        // 断言
        verify(stringRedisTemplate).delete(EXPECTED_KEY);
    }

    @Test
    @DisplayName("释放失败：异常不外抛，等待 TTL 自动过期")
    void release_redisFailure_doesNotThrow() {
        // 前置
        when(stringRedisTemplate.delete(EXPECTED_KEY)).thenThrow(new RedisConnectionFailureException("redis down"));

        // 执行 + 断言
        idempotentGuard.release(SCENE, REQUEST_ID, USER_ID);
    }

    @Test
    @DisplayName("状态判定：占位值为处理中，业务结果与 null 均非处理中")
    void isProcessing_onlyPlaceholderIsTrue() {
        // 断言
        assertThat(idempotentGuard.isProcessing("PROCESSING")).isTrue();
        assertThat(idempotentGuard.isProcessing("9527")).isFalse();
        assertThat(idempotentGuard.isProcessing(null)).isFalse();
        // 反向确认：判定过程不产生该场景之外的 Redis 交互
        verify(stringRedisTemplate, never()).opsForValue();
    }
}
