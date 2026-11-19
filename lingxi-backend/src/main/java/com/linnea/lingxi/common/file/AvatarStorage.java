package com.linnea.lingxi.common.file;

import com.linnea.lingxi.common.exception.BusinessException;
import com.linnea.lingxi.common.exception.ErrorCode;
import com.linnea.lingxi.common.exception.SystemException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 头像本地存储组件：校验、落盘、删除三件事都在这里，业务层只管「存完拿 URL」。
 *
 * <p>安全口径（三条都不靠客户端自述）：
 * ① 类型按文件头魔数判定，不信任原始文件名与 Content-Type；
 * ② 文件名由服务端生成（UUID），原始文件名只用于日志排查，不参与路径拼接；
 * ③ 目录按 userId 分桶，删除时校验目标路径必须仍在存储根目录内，防路径穿越。</p>
 *
 * <p>WHY 用本地磁盘而不是对象存储：当前是单机部署，头像总量小；
 * 抽成本组件后，将来换 OSS 只需替换这一个实现类，调用方不变。</p>
 */
@Slf4j
@Component
public class AvatarStorage {

    /** 头像访问 URL 前缀，必须与 {@code WebMvcConfig} 的静态资源映射保持一致。 */
    public static final String AVATAR_URL_PREFIX = "/uploads/avatar/";

    /**
     * 头像在存储根目录下的子目录名。
     *
     * <p>URL 是 {@code /uploads/avatar/...}，而 {@code /uploads/**} 映射到存储根目录，
     * 因此磁盘上必须真的存在 avatar 这一层，否则 URL 与文件位置对不上。</p>
     */
    private static final String AVATAR_SUB_DIRECTORY = "avatar";

    /** 头像大小上限 2MB：手机直出照片通常 1~3MB，再大由前端压缩后上传。 */
    private static final long MAX_AVATAR_BYTES = 2L * 1024 * 1024;

    /** 判定文件类型所需的最小字节数：WEBP 需要读到第 12 字节才能确认。 */
    private static final int HEADER_BYTES_LENGTH = 12;

    /** 存储根目录的绝对路径（启动时解析一次，避免每次请求做路径运算）。 */
    private final Path storageRootDir;

    /**
     * 构造头像存储组件。
     *
     * @param avatarDir 头像存储根目录，来自配置 app.upload.avatar-dir
     */
    public AvatarStorage(@Value("${app.upload.avatar-dir:uploads}") String avatarDir) {
        // 步骤1：解析为绝对路径并归一化，后续所有路径比较都基于这个根
        this.storageRootDir = Paths.get(avatarDir).toAbsolutePath().normalize();
        log.info("头像存储组件初始化完成, storageRootDir={}, maxAvatarBytes={}",
                this.storageRootDir, MAX_AVATAR_BYTES);
    }

    /**
     * 校验并保存头像文件。
     *
     * @param userId     上传用户ID，用于分目录
     * @param avatarFile 上传的头像文件
     * @return 可直接写入 avatar_url 的相对 URL（以 /uploads/avatar/ 开头）
     */
    public String store(Long userId, MultipartFile avatarFile) {
        // 步骤1：空文件直接拒绝，避免落一个 0 字节文件后前端显示裂图
        if (avatarFile == null || avatarFile.isEmpty()) {
            log.warn("头像上传被拒绝, 文件为空, userId={}", userId);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_EMPTY);
        }
        // 步骤2：大小兜底校验（multipart 层已限制，这里再判一次防止配置被改小窗口绕过）
        if (avatarFile.getSize() > MAX_AVATAR_BYTES) {
            log.warn("头像上传被拒绝, 文件过大, userId={}, size={} bytes", userId, avatarFile.getSize());
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }
        // 步骤3：读取文件内容，类型判定与落盘都基于同一份字节，避免二次读取不一致
        byte[] avatarBytes;
        try {
            avatarBytes = avatarFile.getBytes();
        } catch (IOException readException) {
            log.error("头像文件读取失败, userId={}, size={} bytes", userId, avatarFile.getSize(), readException);
            throw new SystemException("头像文件读取失败, userId=" + userId, readException);
        }
        // 步骤4：按魔数判定真实类型，不属于图片白名单一律拒绝
        String fileExtension = detectImageExtension(avatarBytes);
        if (fileExtension == null) {
            log.warn("头像上传被拒绝, 非白名单图片类型, userId={}, contentType={}, size={} bytes",
                    userId, avatarFile.getContentType(), avatarBytes.length);
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED);
        }
        // 步骤5：按用户分目录落盘，文件名由服务端生成，原始文件名不参与路径
        String storedFileName = UUID.randomUUID().toString().replace("-", "") + "." + fileExtension;
        Path userAvatarDir = storageRootDir.resolve(AVATAR_SUB_DIRECTORY).resolve(String.valueOf(userId));
        Path targetFilePath = userAvatarDir.resolve(storedFileName).normalize();
        try {
            Files.createDirectories(userAvatarDir);
            Files.write(targetFilePath, avatarBytes);
        } catch (IOException writeException) {
            log.error("头像文件写入失败, userId={}, targetFilePath={}", userId, targetFilePath, writeException);
            throw new SystemException("头像文件写入失败, userId=" + userId, writeException);
        }
        // 步骤6：拼出对外 URL，磁盘结构 avatar/{userId}/{file} 与 URL /uploads/avatar/{userId}/{file} 一一对应
        String avatarUrl = AVATAR_URL_PREFIX + userId + "/" + storedFileName;
        log.info("头像文件保存成功, userId={}, avatarUrl={}, size={} bytes, extension={}",
                userId, avatarUrl, avatarBytes.length, fileExtension);
        return avatarUrl;
    }

    /**
     * 删除旧头像文件：新头像落库成功后才调用，失败只记日志不影响主流程。
     *
     * @param avatarUrl 旧的 avatar_url，空串或非本系统路径时直接跳过
     */
    public void deleteByUrl(String avatarUrl) {
        // 步骤1：空值与外部链接一律不处理，只清理本组件生成的路径
        if (avatarUrl == null || avatarUrl.isBlank() || !avatarUrl.startsWith(AVATAR_URL_PREFIX)) {
            log.info("跳过旧头像清理, 非本系统头像路径, avatarUrl={}", avatarUrl);
            return;
        }
        // 步骤2：把 URL 还原成存储根目录内的相对路径并归一化
        String relativePath = avatarUrl.substring("/uploads/".length());
        Path targetFilePath = storageRootDir.resolve(relativePath).normalize();
        // 步骤3：归一化后仍必须在存储根目录内，否则视为非法路径（防 ../ 穿越）
        if (!targetFilePath.startsWith(storageRootDir)) {
            log.warn("跳过旧头像清理, 目标路径越界, avatarUrl={}, targetFilePath={}", avatarUrl, targetFilePath);
            return;
        }
        // 步骤4：删除文件，失败不影响主流程（残留文件由运维定期清理）
        try {
            boolean deleted = Files.deleteIfExists(targetFilePath);
            log.info("旧头像清理完成, avatarUrl={}, targetFilePath={}, deleted={}", avatarUrl, targetFilePath, deleted);
        } catch (IOException deleteException) {
            log.warn("旧头像清理失败, 保留原文件, avatarUrl={}, targetFilePath={}",
                    avatarUrl, targetFilePath, deleteException);
        }
    }

    /**
     * 按文件头魔数判定图片格式。
     *
     * @param fileBytes 文件内容
     * @return 扩展名（jpg/png/gif/webp）；不在白名单内返回 null
     */
    private String detectImageExtension(byte[] fileBytes) {
        // 步骤1：长度不足 12 字节的文件无法可靠判定类型，直接拒绝
        if (fileBytes == null || fileBytes.length < HEADER_BYTES_LENGTH) {
            return null;
        }
        // 步骤2：JPEG 固定以 FF D8 FF 开头
        if ((fileBytes[0] & 0xFF) == 0xFF && (fileBytes[1] & 0xFF) == 0xD8 && (fileBytes[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        // 步骤3：PNG 固定 8 字节签名 89 50 4E 47 0D 0A 1A 0A
        if ((fileBytes[0] & 0xFF) == 0x89 && fileBytes[1] == 0x50 && fileBytes[2] == 0x4E
                && fileBytes[3] == 0x47 && fileBytes[4] == 0x0D && fileBytes[5] == 0x0A
                && fileBytes[6] == 0x1A && fileBytes[7] == 0x0A) {
            return "png";
        }
        // 步骤4：GIF 以 ASCII "GIF8" 开头（GIF87a / GIF89a）
        if (fileBytes[0] == 'G' && fileBytes[1] == 'I' && fileBytes[2] == 'F' && fileBytes[3] == '8') {
            return "gif";
        }
        // 步骤5：WEBP 是 RIFF 容器，第 8~11 字节为 "WEBP"
        if (fileBytes[0] == 'R' && fileBytes[1] == 'I' && fileBytes[2] == 'F' && fileBytes[3] == 'F'
                && fileBytes[8] == 'W' && fileBytes[9] == 'E' && fileBytes[10] == 'B' && fileBytes[11] == 'P') {
            return "webp";
        }
        // 步骤6：其余类型（含 SVG：可内嵌脚本，不适合直接当图片托管）一律拒绝
        return null;
    }
}
