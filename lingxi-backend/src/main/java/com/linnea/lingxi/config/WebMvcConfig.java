package com.linnea.lingxi.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Web MVC 配置：把本地上传目录映射成静态资源路径。
 *
 * <p>WHY 需要这一层：头像文件不经过 Controller，直接由静态资源处理器返回；
 * avatar_url 存的是相对 URL（{@code /uploads/avatar/...}），换域名、换端口都不用改库。</p>
 */
@Slf4j
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** 静态资源访问前缀：与 AvatarStorage 返回的 URL 前缀保持一致。 */
    private static final String UPLOAD_URL_PATTERN = "/uploads/**";

    /** 本地上传根目录，来自配置 app.upload.avatar-dir。 */
    private final String uploadDir;

    /**
     * 构造 Web MVC 配置。
     *
     * @param uploadDir 本地上传根目录
     */
    public WebMvcConfig(@Value("${app.upload.avatar-dir:uploads}") String uploadDir) {
        this.uploadDir = uploadDir;
    }

    /**
     * 注册上传目录的静态资源映射。
     *
     * @param registry 静态资源注册表
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 步骤1：解析为绝对路径的 URI，Windows 下必须先转成 file:/// 形式，否则冒号会被当成协议分隔符
        Path storageRootDir = Paths.get(uploadDir).toAbsolutePath().normalize();
        String storageLocation = storageRootDir.toUri().toString();
        // 步骤2：目录 location 必须以斜杠结尾，否则会被当成文件前缀拼接出错
        if (!storageLocation.endsWith("/")) {
            storageLocation = storageLocation + "/";
        }
        // 步骤3：注册映射，使 /uploads/avatar/1/xxx.png 对应 {uploadDir}/avatar/1/xxx.png
        registry.addResourceHandler(UPLOAD_URL_PATTERN).addResourceLocations(storageLocation);
        log.info("静态资源映射完成, urlPattern={}, location={}", UPLOAD_URL_PATTERN, storageLocation);
    }
}
