package com.lq.deepseek.service.support;

import com.lq.deepseek.common.BusinessException;
import com.lq.deepseek.common.ErrorCode;
import com.lq.deepseek.config.props.LqProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * 文件落盘与指纹计算。
 *
 * <p>从简历中心里抽出来的公共能力：JD 原件、知识库文档都要走同一套
 * "指纹命名 + 按用户/月份分目录"的存储约定，避免每来一个新业务就复制一遍落盘代码。
 */
@Component
public class FileStorageService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private final LqProperties properties;

    public FileStorageService(LqProperties properties) {
        this.properties = properties;
    }

    /** 文件内容 SHA-256，业务侧据此做"同一文件不重复解析"判定。 */
    public String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "计算文件指纹失败", e);
        }
    }

    /** 扩展名（小写、不含点），无扩展名时返回空串。 */
    public String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }

    public String readText(byte[] bytes) {
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 落盘并返回相对路径。
     *
     * <p>用内容指纹命名而不是原始文件名：同名不同内容的两份 JD 不会互相覆盖，
     * 且重复上传天然指向同一路径。
     */
    public String store(Long userId, String sha256, String ext, byte[] bytes) {
        Path root = Paths.get(properties.getStorage().getLocalRoot()).toAbsolutePath().normalize();
        String suffix = ext == null || ext.isEmpty() ? "bin" : ext;
        String relative = userId + "/" + LocalDate.now().format(MONTH) + "/" + sha256 + "." + suffix;
        Path target = root.resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件落盘失败", e);
        }
        return relative;
    }

    /** 读取已落盘文件，用于知识库重建索引等场景。 */
    public byte[] load(String objectKey) {
        Path root = Paths.get(properties.getStorage().getLocalRoot()).toAbsolutePath().normalize();
        try {
            return Files.readAllBytes(root.resolve(objectKey));
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND, "文件已不存在：" + objectKey, e);
        }
    }
}
