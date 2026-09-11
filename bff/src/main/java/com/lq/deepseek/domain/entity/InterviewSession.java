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
 * 面试会话（Python Agent 为状态权威，BFF 仅存投影快照）。
 *
 * <p>表结构已由 V1 创建，此处仅补充实体映射；state_snapshot 用 JacksonTypeHandler 读写 jsonb。
 */
@Data
@TableName(value = "interview_sessions", autoResultMap = true)
public class InterviewSession implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long resumeAssetId;

    private Long jdAssetId;

    private String title;

    /** TEXT / VIDEO */
    private String mode;

    /** CREATED / RUNNING / PAUSED / FINISHED / ABORTED */
    private String status;

    private String currentStage;

    private Integer turnCount;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> stateSnapshot;

    private OffsetDateTime startedAt;

    private OffsetDateTime finishedAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    private Integer deleted;
}
