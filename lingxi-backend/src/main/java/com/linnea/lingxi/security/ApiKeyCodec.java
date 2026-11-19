package com.linnea.lingxi.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Agent API Key 的生成与摘要工具。
 *
 * <p>WHY 库内只存摘要：api_key 等同于 Agent 的长期口令，一库泄露即全部失守；
 * 存 SHA-256 摘要后，即使库被读走也无法反推可用密钥，校验时对客户端提交值做同样摘要再比对。
 * 密钥本身只在「生成/重置」接口响应中出现一次，之后无法回显，丢失只能重置。</p>
 *
 * <p>生成格式：{@code lx_} 前缀 + 32 字节安全随机数的 Base64URL（无填充），
 * 前缀便于运维在日志与配置中识别泄漏来源。</p>
 */
public final class ApiKeyCodec {

    /** 密钥前缀：标识这是 Lingxi 签发的 Agent 密钥。 */
    private static final String KEY_PREFIX = "lx_";

    /** 随机字节数：256 bit，与 HS256 密钥强度同级，暴力枚举不可行。 */
    private static final int KEY_BYTES = 32;

    /** 摘要算法：SHA-256，输出 64 位十六进制，适配 DDL 中 VARCHAR(128) 的 api_key 列。 */
    private static final String DIGEST_ALGORITHM = "SHA-256";

    /** 安全随机源：线程安全，可全局复用，禁止使用 Random。 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 私有构造：工具类不允许实例化。
     */
    private ApiKeyCodec() {
    }

    /**
     * 生成一枚新的 Agent API Key 明文。
     *
     * @return 形如 lx_xxxxxxxx 的密钥明文，仅在重置接口响应中返回一次
     */
    public static String generate() {
        // 步骤1：取 32 字节安全随机数
        byte[] keyBytes = new byte[KEY_BYTES];
        SECURE_RANDOM.nextBytes(keyBytes);
        // 步骤2：URL 安全 Base64 编码并加前缀，去掉填充符避免复制时被截断
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(keyBytes);
    }

    /**
     * 计算密钥摘要，用于落库与比对。
     *
     * @param rawApiKey 客户端提交的密钥明文
     * @return 64 位十六进制摘要
     */
    public static String digest(String rawApiKey) {
        // 步骤1：按 UTF-8 取字节做 SHA-256
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            byte[] digestBytes = messageDigest.digest(rawApiKey.getBytes(StandardCharsets.UTF_8));
            // 步骤2：转小写十六进制，便于入库与等值查询
            return HexFormat.of().formatHex(digestBytes);
        } catch (NoSuchAlgorithmException noSuchAlgorithmException) {
            // SHA-256 是 JDK 强制实现的算法，缺失说明运行环境异常，直接失败而不是降级存明文
            throw new IllegalStateException("当前 JVM 不支持 SHA-256，无法计算 API Key 摘要", noSuchAlgorithmException);
        }
    }
}
