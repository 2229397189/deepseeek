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
 * AI 调用运行记录。
 *
 * <p>这是 Agent Invocation Gateway 的审计台账：每一次用户侧请求（含命中结果回放的请求）
 * 都落一行，用 {@code input_digest} 串起"同一输入的多次调用"，是排查"为什么这次没走缓存 /
 * 为什么这次调用了两次下游"的唯一依据。
 */
@Data
@TableName(value = "agent_runs", autoResultMap = true)
public class AgentRun implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String runId;

    private Long userId;

    /** 与 agent_runs.ck_run_biz 约束保持一致 */
    private String bizType;

    private Long bizId;

    private String stage;

    /** PENDING / RUNNING / SUCCEEDED / FAILED / CANCELED */
    private String status;

    private String specHash;

    /** 输入摘要（SHA-256），single-flight 去重键与对账依据 */
    private String inputDigest;

    private Integer attempt;

    /** 实际执行者实例标识，用于定位多实例下的重复执行 */
    private String ownerInstance;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> output;

    private String errorCode;

    private String errorMsg;

    private Integer promptTokens;

    private Integer outputTokens;

    private Long costCredit;

    private Integer latencyMs;

    /** OWNER / REPLAY / LOCAL_OWNER / LOCAL_REPLAY，非表字段，仅供接口展示 */
    @TableField(exist = false)
    private String flightMode;

    private OffsetDateTime startedAt;

    private OffsetDateTime finishedAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
