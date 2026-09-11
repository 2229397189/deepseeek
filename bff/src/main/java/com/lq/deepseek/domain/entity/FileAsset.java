package com.lq.deepseek.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 文件资产（简历 / JD / 知识库文档）。
 *
 * <p>{@code sha256} + 用户维度唯一：同一份简历重复上传必须命中同一条资产。
 * 这既是为了省存储，更是为了**不重复扣费**——用户在"下载目录里两份同名简历"之间反复点击时，
 * 不能每次解析都走一遍 AI 调用。
 */
@Data
@TableName(value = "file_assets", autoResultMap = true)
public class FileAsset implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** RESUME / JD / KB_DOC */
    private String bizType;

    private String fileName;

    /** 本地存储相对路径（存储实现可替换为对象存储 key） */
    private String objectKey;

    private String contentType;

    private Long sizeBytes;

    /** 文件内容 SHA-256，唯一索引 uk_file_assets_user_sha 的组成部分 */
    private String sha256;

    /** PENDING / PARSING / SUCCESS / FAILED */
    private String parseStatus;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> parseResult;

    private String errorMsg;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    private Integer deleted;
}
