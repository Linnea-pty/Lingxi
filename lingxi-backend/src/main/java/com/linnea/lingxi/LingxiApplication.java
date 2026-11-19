package com.linnea.lingxi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Lingxi 应用启动类：开启 Spring Boot 自动配置，并以本类所在包为根扫描全部组件。
 *
 * <p>本项目是 Agent 与 Web 双入口的社交服务：Web 端走 JWT 鉴权，
 * Agent 侧后续接入 api_key 鉴权。启动依赖 MySQL 与 Redis；
 * JWT 签名密钥由环境变量 JWT_SECRET 注入，缺失时启动即失败（见 JwtTokenProvider）。</p>
 */
@SpringBootApplication
public class LingxiApplication {

    /**
     * 进程入口。
     *
     * @param args 启动参数，可覆盖 application.properties 中的同名配置
     */
    public static void main(String[] args) {
        SpringApplication.run(LingxiApplication.class, args);
    }
}
