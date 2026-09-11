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
 * 知识库分片（V2 已建表，此处补实体映射）。
 *
 * <p>{@code embedding}（VECTOR）与 {@code tsv}（GENERATED ALWAYS）由底层维护：
 * BFF 侧不读写向量列，故用 {@code exist=false} 排除，避免插入/映射冲突。
 */
@Data
@TableName(value = "kb_chunks", autoResultMap = true)
public class KbChunk implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long documentId;

    private Long userId;

    private Long sessionId;

    private String bizType;

    private Integer chunkIndex;

    private String content;

    private Integer tokenCount;

    /** VECTOR(1024)：由 agent / 向量服务写入，BFF 不处理 */
    @TableField(exist = false)
    private Object embedding;

    /** TSVECTOR GENERATED ALWAYS：数据库自动维护，BFF 不处理 */
    @TableField(exist = false)
    private Object tsv;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
