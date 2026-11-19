package com.linnea.lingxi.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Objects;

/**
 * 令牌服务端状态存储（Redis）：刷新令牌登记与轮换、访问令牌吊销。
 *
 * <p>JWT 本身无状态，登出与轮换必须靠服务端记录才能生效，这里只用两种键：</p>
 * <ul>
 *   <li>{@code lingxi:token:refresh:{jti}} → 值存用户ID，登录时写入，换发时原子取走（GETDEL）：
 *       取不到就说明该刷新令牌已被用过或已登出，直接拒绝，天然防重放。</li>
 *   <li>{@code lingxi:token:revoked:access:{jti}} → 存在即代表该访问令牌已吊销，
 *       保留时长由 app.jwt.revoked-token-retention 决定（必须不短于访问令牌有效期）。</li>
 * </ul>
 *
 * <p>失败策略是分开的，因为两条路径的代价不同：</p>
 * <ul>
 *   <li>刷新令牌相关操作按「失败即拒绝」处理（fail-closed）：Redis 异常时无法证明该令牌未被使用过，
 *       宁可让用户重新登录，也不能凭一个来历不明的刷新令牌换出新的访问令牌。</li>
 *   <li>访问令牌吊销检查按「失败即放行」处理（fail-open）：这是每个请求都要走的路径，
 *       Redis 抖动就全站 401 的代价远大于极端情况下的密钥泄露；异常一律记 ERROR 以便告警介入。</li>
 * </ul>
 */
@Slf4j
@Component
public class TokenStore {

    /** 刷新令牌登记的键前缀，后缀为刷新令牌的 jti。 */
    private static final String REFRESH_TOKEN_KEY_PREFIX = "lingxi:token:refresh:";

    /** 访问令牌吊销标记的键前缀，后缀为访问令牌的 jti。 */
    private static final String REVOKED_ACCESS_TOKEN_KEY_PREFIX = "lingxi:token:revoked:access:";

    /** 吊销标记的占位值，键存在即代表已吊销。 */
    private static final String REVOKED_VALUE = "1";

    /** Redis 操作模板。 */
    private final StringRedisTemplate stringRedisTemplate;

    /** 吊销标记保留时长，单位毫秒，配置项 app.jwt.revoked-token-retention。 */
    private final long revokedTokenRetention;

    /**
     * 构造令牌状态存储。
     *
     * @param stringRedisTemplate     Redis 操作模板
     * @param revokedTokenRetention   吊销标记保留时长，单位毫秒
     */
    public TokenStore(StringRedisTemplate stringRedisTemplate,
                      @Value("${app.jwt.revoked-token-retention}") long revokedTokenRetention) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.revokedTokenRetention = revokedTokenRetention;
    }

    /**
     * 登记刷新令牌，使其具备换发资格。
     *
     * @param tokenId        刷新令牌的 jti
     * @param userId         令牌所属用户
     * @param timeToLiveInMillis 与刷新令牌有效期一致，键到期自动清理，无需定时任务
     */
    public void registerRefreshToken(String tokenId, Long userId, long timeToLiveInMillis) {
        // 步骤1：写入 键=jti 值=用户ID 的记录，TTL 与令牌同寿，避免无效键长期堆积
        try {
            stringRedisTemplate.opsForValue().set(buildRefreshTokenKey(tokenId), String.valueOf(userId),
                    Duration.ofMillis(timeToLiveInMillis));
            log.debug("刷新令牌登记成功, userId={}, tokenId={}", userId, tokenId);
        } catch (RuntimeException redisException) {
            // 步骤2：登记失败只告警不阻断登录：访问令牌仍可正常使用，只是本次刷新令牌不可换发
            log.error("刷新令牌登记失败, userId={}, tokenId={}", userId, tokenId, redisException);
        }
    }

    /**
     * 取走并校验刷新令牌（换发时的原子消费，兼作轮换）。
     *
     * <p>用 GETDEL 而不是 GET + DEL：并发刷新同一令牌时只有一个请求能取到值，
     * 另一个必然取空并被拒绝，避免一个刷新令牌换出两套令牌。</p>
     *
     * @param tokenId 刷新令牌的 jti
     * @param userId  令牌声称的用户ID
     * @return true 表示该刷新令牌有效且属于该用户，已同时作废；false 表示不可用
     */
    public boolean consumeRefreshToken(String tokenId, Long userId) {
        // 步骤1：原子取出并删除登记记录
        String registeredUserId;
        try {
            registeredUserId = stringRedisTemplate.opsForValue().getAndDelete(buildRefreshTokenKey(tokenId));
        } catch (RuntimeException redisException) {
            // 步骤2：Redis 异常按 fail-closed 处理，不允许无凭据换发
            log.error("刷新令牌消费失败，按无效处理, userId={}, tokenId={}", userId, tokenId, redisException);
            return false;
        }
        // 步骤3：未登记（已用过/已登出/伪造）直接拒绝
        if (!StringUtils.hasText(registeredUserId)) {
            log.warn("刷新令牌未登记或已被使用, userId={}, tokenId={}", userId, tokenId);
            return false;
        }
        // 步骤4：登记记录必须属于同一用户，防止拿到他人令牌后换发
        if (!Objects.equals(registeredUserId, String.valueOf(userId))) {
            log.warn("刷新令牌归属不符, 声称userId={}, 登记userId={}, tokenId={}", userId, registeredUserId, tokenId);
            return false;
        }
        return true;
    }

    /**
     * 吊销刷新令牌（登出时调用）。
     *
     * @param tokenId 刷新令牌的 jti
     */
    public void revokeRefreshToken(String tokenId) {
        // 步骤1：直接删除登记记录，之后该刷新令牌无法再换发
        try {
            stringRedisTemplate.delete(buildRefreshTokenKey(tokenId));
            log.info("刷新令牌已吊销, tokenId={}", tokenId);
        } catch (RuntimeException redisException) {
            log.error("刷新令牌吊销失败, tokenId={}", tokenId, redisException);
        }
    }

    /**
     * 写入访问令牌吊销标记（登出时调用）。
     *
     * @param tokenId 访问令牌的 jti
     */
    public void revokeAccessToken(String tokenId) {
        // 步骤1：写入带 TTL 的标记键，TTL 到期后键消失，此时令牌本身也已自然过期
        try {
            stringRedisTemplate.opsForValue().set(buildRevokedAccessTokenKey(tokenId), REVOKED_VALUE,
                    Duration.ofMillis(revokedTokenRetention));
            log.info("访问令牌已吊销, tokenId={}, 吊销标记保留毫秒={}", tokenId, revokedTokenRetention);
        } catch (RuntimeException redisException) {
            log.error("访问令牌吊销失败（登出未生效）, tokenId={}", tokenId, redisException);
        }
    }

    /**
     * 判断访问令牌是否已被吊销。
     *
     * @param tokenId 访问令牌的 jti
     * @return true 表示已吊销；Redis 异常时返回 false（fail-open，见类注释）
     */
    public boolean isAccessTokenRevoked(String tokenId) {
        // 步骤1：键存在即已吊销；异常按放行处理并记 ERROR，便于告警而非静默
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(buildRevokedAccessTokenKey(tokenId)));
        } catch (RuntimeException redisException) {
            log.error("访问令牌吊销状态查询失败，按未吊销处理, tokenId={}", tokenId, redisException);
            return false;
        }
    }

    /**
     * 拼接刷新令牌登记键。
     *
     * @param tokenId 刷新令牌的 jti
     * @return Redis 键
     */
    private String buildRefreshTokenKey(String tokenId) {
        return REFRESH_TOKEN_KEY_PREFIX + tokenId;
    }

    /**
     * 拼接访问令牌吊销标记键。
     *
     * @param tokenId 访问令牌的 jti
     * @return Redis 键
     */
    private String buildRevokedAccessTokenKey(String tokenId) {
        return REVOKED_ACCESS_TOKEN_KEY_PREFIX + tokenId;
    }
}
