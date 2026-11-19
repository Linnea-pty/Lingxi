package com.linnea.lingxi.security;

/**
 * 已签发的令牌及其元信息。
 *
 * <p>WHY 需要把 jti 一起返回：刷新令牌要按 jti 在 Redis 登记，登出要按 jti 写吊销标记，
 * 若只返回令牌字符串，调用方还得再解析一次才能拿到 jti，等于把解析错误分散到各处。</p>
 *
 * @param tokenValue      令牌字符串，直接返回给客户端，禁止写入日志
 * @param tokenId         令牌唯一标识（JWT 的 jti 声明），用于服务端登记与吊销
 * @param expiresInMillis 有效期，单位毫秒，供前端安排刷新时机
 */
public record IssuedToken(String tokenValue, String tokenId, long expiresInMillis) {
}
