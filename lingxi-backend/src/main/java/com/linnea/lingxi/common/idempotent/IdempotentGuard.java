package com.linnea.lingxi.common.idempotent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 写接口幂等栅栏：用 Redis 的 SET NX 抢占「请求处理权」，避免同一业务请求被重复执行。
 *
 * <p>WHY 用 Redis 而不是数据库唯一键：幂等键是客户端生成的短期凭证（分钟级有效），
 * 不需要长期留存与审计，放 Redis 可自动过期，也不必为每张业务表加列与唯一索引。</p>
 *
 * <p>失败策略是 fail-open：Redis 不可用或命令失败时记录 ERROR（带堆栈，供监控告警）并放行请求，
 * 不让缓存故障拖垮整个发布/评论功能。这是刻意的可用性取舍——幂等去重只负责「减少重复」，
 * 不是业务正确性的唯一保证：点赞另有唯一键 uk_user_post_deleted 兜底；
 * 发布动态与发表评论没有唯一键，降级窗口内同一 requestId 的重复提交会落多条数据，
 * 属于已接受的代价，出现 ERROR 日志时应尽快恢复 Redis。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotentGuard {

    /** 幂等键统一前缀，便于在 Redis 中与其他业务键区分。 */
    private static final String KEY_PREFIX = "lingxi:idempotent:";

    /** 处理中占位值：表示该幂等键已被某次请求抢占，但业务结果尚未回填。 */
    private static final String PROCESSING_VALUE = "PROCESSING";

    /** 幂等键存活时长：覆盖一次请求的最长处理时间，超时后允许客户端重试。 */
    private static final Duration TIME_TO_LIVE = Duration.ofMinutes(30);

    /** 日志中幂等键的展示长度，避免长串刷屏（幂等键不含敏感信息，仅为可读性）。 */
    private static final int LOG_KEY_PREFIX_LENGTH = 8;

    /** Redis 字符串模板：幂等键与结果值都是字符串，无需 JSON 序列化。 */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 抢占幂等键，判断本次请求是否为首次执行。
     *
     * @param scene     业务场景标识，如 post:publish
     * @param requestId 客户端生成的请求唯一标识
     * @param userId    当前用户ID，参与拼键避免不同用户撞同一 requestId
     * @return true-首次执行；false-该幂等键已被占用（可能是并发重复提交，也可能是已完成）
     */
    public boolean tryAcquire(String scene, String requestId, Long userId) {
        // 步骤1：拼键并原子抢占，SET NX 保证同一时刻只有一个请求成功
        String idempotentKey = buildKey(scene, requestId, userId);
        try {
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(idempotentKey, PROCESSING_VALUE, TIME_TO_LIVE);
            boolean firstRequest = Boolean.TRUE.equals(acquired);
            log.info("幂等键抢占完成, scene={}, requestId={}, firstRequest={}",
                    scene, abbreviate(requestId), firstRequest);
            return firstRequest;
        } catch (RuntimeException redisException) {
            // 步骤2：Redis 故障时降级放行（fail-open）；用 ERROR 触发告警，因为此窗口内重复提交会落多条数据
            log.error("幂等栅栏不可用, 已降级为不做去重, scene={}, requestId={}",
                    scene, abbreviate(requestId), redisException);
            return true;
        }
    }

    /**
     * 回填幂等键对应的业务结果，供后续重复请求直接返回既有结果。
     *
     * @param scene       业务场景标识
     * @param requestId   客户端生成的请求唯一标识
     * @param userId      当前用户ID
     * @param resultValue 业务结果标识，如新建记录的ID
     */
    public void bindResult(String scene, String requestId, Long userId, String resultValue) {
        // 步骤1：覆盖占位值，TTL 重置为完整时长，重复请求据此返回首次结果
        String idempotentKey = buildKey(scene, requestId, userId);
        try {
            stringRedisTemplate.opsForValue().set(idempotentKey, resultValue, TIME_TO_LIVE);
            log.info("幂等键回填结果, scene={}, requestId={}", scene, abbreviate(requestId));
        } catch (RuntimeException redisException) {
            // 回填失败不影响本次业务，但后续重复请求会按「处理中」被拒绝，需告警介入
            log.error("幂等键回填失败, 重复请求将按处理中拒绝, scene={}, requestId={}",
                    scene, abbreviate(requestId), redisException);
        }
    }

    /**
     * 查询幂等键当前记录的值。
     *
     * @param scene     业务场景标识
     * @param requestId 客户端生成的请求唯一标识
     * @param userId    当前用户ID
     * @return 幂等键的值；键不存在或 Redis 不可用时返回 null
     */
    public String queryResult(String scene, String requestId, Long userId) {
        // 步骤1：直接读取键值，不存在返回 null
        String idempotentKey = buildKey(scene, requestId, userId);
        try {
            String resultValue = stringRedisTemplate.opsForValue().get(idempotentKey);
            log.info("幂等键查询完成, scene={}, requestId={}, hasResult={}",
                    scene, abbreviate(requestId), resultValue != null);
            return resultValue;
        } catch (RuntimeException redisException) {
            // 查询失败按未命中处理：调用方会转成「重复提交」拒绝，不会重复写库
            log.error("幂等键查询失败, 按未命中处理, scene={}, requestId={}",
                    scene, abbreviate(requestId), redisException);
            return null;
        }
    }

    /**
     * 释放幂等键：业务执行失败时调用，让客户端可以立即重试。
     *
     * @param scene     业务场景标识
     * @param requestId 客户端生成的请求唯一标识
     * @param userId    当前用户ID
     */
    public void release(String scene, String requestId, Long userId) {
        // 步骤1：删除键，失败只告警（键有 TTL，最坏情况是等待自动过期）
        String idempotentKey = buildKey(scene, requestId, userId);
        try {
            stringRedisTemplate.delete(idempotentKey);
            log.info("幂等键已释放, scene={}, requestId={}", scene, abbreviate(requestId));
        } catch (RuntimeException redisException) {
            // 释放失败时键会在 TTL 后自动过期，客户端需等待，故仍需告警
            log.error("幂等键释放失败, 等待 TTL 自动过期, scene={}, requestId={}",
                    scene, abbreviate(requestId), redisException);
        }
    }

    /**
     * 判断幂等键的值是否仍处于「处理中」状态。
     *
     * @param resultValue 幂等键的值，可为 null
     * @return true-正在处理中（并发重复提交）；false-已回填业务结果或键不存在
     */
    public boolean isProcessing(String resultValue) {
        // 步骤1：只有占位值才算处理中，null 视为键已消失
        boolean processing = PROCESSING_VALUE.equals(resultValue);
        log.info("幂等键状态判定完成, processing={}", processing);
        return processing;
    }

    /**
     * 拼接幂等键：场景 + 用户 + 请求标识。
     *
     * @param scene     业务场景标识
     * @param requestId 客户端请求标识
     * @param userId    当前用户ID
     * @return Redis 键名
     */
    private String buildKey(String scene, String requestId, Long userId) {
        // 步骤1：按固定顺序拼接，用户维度隔离避免跨用户撞键
        return KEY_PREFIX + scene + ":" + userId + ":" + requestId;
    }

    /**
     * 截断幂等键用于日志展示。
     *
     * @param requestId 客户端请求标识
     * @return 前 8 位；不足 8 位时原样返回
     */
    private String abbreviate(String requestId) {
        // 步骤1：null 与短串直接返回，长串截断
        if (requestId == null || requestId.length() <= LOG_KEY_PREFIX_LENGTH) {
            return requestId;
        }
        return requestId.substring(0, LOG_KEY_PREFIX_LENGTH) + "...";
    }
}
