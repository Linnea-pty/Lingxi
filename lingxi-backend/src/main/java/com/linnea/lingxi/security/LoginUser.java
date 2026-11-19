package com.linnea.lingxi.security;

/**
 * 认证主体：JWT 或 Agent API Key 校验通过后写入 SecurityContext 的当前调用者。
 *
 * <p>只放鉴权与归属判断必需的字段，不放密码哈希、api_key 等敏感信息。</p>
 *
 * @param userId   用户ID，来自令牌 subject 或 api_key 命中记录，用于业务归属校验
 * @param username 登录用户名，用于日志与展示
 * @param agent    是否为 Agent 身份：true 表示通过 api_key 认证，代发动态需要置顶且来源记为 agent
 * @param tokenId  本次请求所用访问令牌的 jti，登出时据此吊销当前令牌；API Key 认证时为 null
 */
public record LoginUser(Long userId, String username, boolean agent, String tokenId) {
}
