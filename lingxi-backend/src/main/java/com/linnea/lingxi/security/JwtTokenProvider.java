package com.linnea.lingxi.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * 令牌的签发与校验：访问令牌（access）与刷新令牌（refresh）共用密钥、按 type 声明区分用途。
 *
 * <p>签名密钥来自配置项 app.jwt.secret（Base64 编码，由环境变量注入），代码中不保存任何密钥常量；
 * 密钥不足 256 bit 时启动即失败，避免弱密钥悄悄上线。</p>
 *
 * <p>两种令牌的差别只在有效期与用途：access 用于每次请求鉴权（短，2 小时），
 * refresh 只能换发新令牌（长，7 天），且必须凭服务端登记过的 jti 才能使用，见 {@link TokenStore}。</p>
 */
@Slf4j
@Component
public class JwtTokenProvider {

    /** 令牌类型声明键：用于区分访问令牌与刷新令牌，防止刷新令牌被当成访问令牌直接调接口。 */
    public static final String TOKEN_TYPE_CLAIM = "type";

    /** 访问令牌类型值：只有该类型的令牌允许用于接口鉴权。 */
    public static final String ACCESS_TOKEN_TYPE = "access";

    /** 刷新令牌类型值：只能在换发接口使用，出现在 Authorization 头里一律拒绝。 */
    public static final String REFRESH_TOKEN_TYPE = "refresh";

    /** 用户名声明键。 */
    private static final String USERNAME_CLAIM = "username";

    /** HS256 要求的最小密钥长度（256 bit = 32 字节）。 */
    private static final int MIN_SECRET_BYTES = 32;

    /** HMAC 签名密钥。 */
    private final SecretKey secretKey;

    /** 访问令牌有效期，单位毫秒。 */
    private final long accessTokenExpiration;

    /** 刷新令牌有效期，单位毫秒。 */
    private final long refreshTokenExpiration;

    /**
     * 初始化签名密钥并校验强度。
     *
     * @param base64Secret           Base64 编码的 HMAC 密钥，来自环境变量 JWT_SECRET
     * @param accessTokenExpiration  访问令牌有效期，单位毫秒
     * @param refreshTokenExpiration 刷新令牌有效期，单位毫秒
     * @throws IllegalStateException 密钥缺失或强度不足时抛出，阻止应用启动
     */
    public JwtTokenProvider(@Value("${app.jwt.secret}") String base64Secret,
                            @Value("${app.jwt.access-token-expiration}") long accessTokenExpiration,
                            @Value("${app.jwt.refresh-token-expiration}") long refreshTokenExpiration) {
        // 步骤1：解码 Base64 密钥并校验长度，强度不足直接启动失败
        byte[] keyBytes = Decoders.BASE64.decode(base64Secret);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("app.jwt.secret 强度不足：HS256 要求至少 256 bit（32 字节）的 Base64 密钥");
        }
        // 步骤2：构建签名密钥并保存两种令牌的有效期
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        this.accessTokenExpiration = accessTokenExpiration;
        this.refreshTokenExpiration = refreshTokenExpiration;
        // 步骤3：只记录密钥位数与有效期，密钥本身绝不落日志
        log.info("签名密钥初始化完成, 密钥位数={}, 访问令牌有效期毫秒={}, 刷新令牌有效期毫秒={}",
                keyBytes.length * 8, accessTokenExpiration, refreshTokenExpiration);
    }

    /**
     * 签发访问令牌。
     *
     * @param userId   用户ID，写入 subject
     * @param username 登录用户名，写入自定义声明
     * @return 令牌字符串、jti 与有效期
     */
    public IssuedToken generateAccessToken(Long userId, String username) {
        // 步骤1：签发带 type=access 的令牌，jti 用于登出时按单令牌吊销
        return buildToken(userId, username, ACCESS_TOKEN_TYPE, accessTokenExpiration);
    }

    /**
     * 签发刷新令牌。
     *
     * @param userId   用户ID，写入 subject
     * @param username 登录用户名，写入自定义声明
     * @return 令牌字符串、jti 与有效期；调用方必须把 jti 登记到 {@link TokenStore}
     */
    public IssuedToken generateRefreshToken(Long userId, String username) {
        // 步骤1：签发带 type=refresh 的令牌，jti 是换发时唯一可用的凭据
        return buildToken(userId, username, REFRESH_TOKEN_TYPE, refreshTokenExpiration);
    }

    /**
     * 校验签名与有效期并解析访问令牌。
     *
     * @param accessToken 待解析的访问令牌
     * @return 令牌声明，包含 subject（用户ID）、jti 与用户名
     * @throws JwtException             签名非法、令牌过期、格式错误或类型不是 access 时抛出
     * @throws IllegalArgumentException 令牌为空时抛出
     */
    public Claims parseAccessToken(String accessToken) {
        // 步骤1：统一解析入口，类型不符时在 parseToken 内部抛 JwtException
        return parseToken(accessToken, ACCESS_TOKEN_TYPE);
    }

    /**
     * 校验签名与有效期并解析刷新令牌。
     *
     * @param refreshToken 待解析的刷新令牌
     * @return 令牌声明，包含 subject（用户ID）与 jti
     * @throws JwtException             签名非法、令牌过期、格式错误或类型不是 refresh 时抛出
     * @throws IllegalArgumentException 令牌为空时抛出
     */
    public Claims parseRefreshToken(String refreshToken) {
        // 步骤1：统一解析入口，类型不符时在 parseToken 内部抛 JwtException
        return parseToken(refreshToken, REFRESH_TOKEN_TYPE);
    }

    /**
     * 组装并签名令牌。
     *
     * @param userId      用户ID
     * @param username    登录用户名
     * @param tokenType   令牌类型，access 或 refresh
     * @param expireMillis 有效期，单位毫秒
     * @return 令牌字符串、jti 与有效期
     */
    private IssuedToken buildToken(Long userId, String username, String tokenType, long expireMillis) {
        // 步骤1：计算签发时间、过期时间与唯一标识
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusMillis(expireMillis);
        String tokenId = UUID.randomUUID().toString();
        // 步骤2：组装声明并签名，jti 由 jjwt 的 id() 写入标准声明，便于服务端登记与吊销
        String tokenValue = Jwts.builder()
                .id(tokenId)
                .subject(String.valueOf(userId))
                .claim(USERNAME_CLAIM, username)
                .claim(TOKEN_TYPE_CLAIM, tokenType)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey)
                .compact();
        // 步骤3：只记录类型、jti 与过期时间，令牌本身绝不落日志
        log.info("令牌签发成功, tokenType={}, userId={}, tokenId={}, expiresAt={}",
                tokenType, userId, tokenId, expiresAt);
        return new IssuedToken(tokenValue, tokenId, expireMillis);
    }

    /**
     * 校验签名、有效期与令牌类型后解析声明。
     *
     * @param tokenValue       待解析的令牌
     * @param expectedTokenType 期望的令牌类型
     * @return 令牌声明
     * @throws JwtException 签名非法、已过期、格式错误或类型不符时抛出
     */
    private Claims parseToken(String tokenValue, String expectedTokenType) {
        // 步骤1：校验签名与有效期，失败由 jjwt 抛出 JwtException，交由调用方转 401
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(tokenValue)
                .getPayload();
        // 步骤2：校验令牌类型，防止刷新令牌被当成访问令牌使用（反之亦然）
        String actualTokenType = claims.get(TOKEN_TYPE_CLAIM, String.class);
        if (!expectedTokenType.equals(actualTokenType)) {
            log.warn("令牌类型校验失败, tokenId={}, expectedType={}, actualType={}",
                    claims.getId(), expectedTokenType, actualTokenType);
            throw new JwtException("令牌类型不是 " + expectedTokenType);
        }
        return claims;
    }
}
