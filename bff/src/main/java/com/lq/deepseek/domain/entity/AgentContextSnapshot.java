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
 * Agent Context Snapshot：一次 AI 调用前的完整上下文打包。
 *
 * <p>快照记录"这次调用看到了什么"——简历画像 / JD / 历史结论 / 检索片段统一打包为
 * 可追踪的 JSONB，配合 token_used / degraded 让上下文质量可审计。表结构见 V2。
 */
@Data
@TableName(value = "agent_context_snapshots", autoResultMap = true)
public class AgentContextSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long sessionId;

    /** DECIDE / INTERVIEW / RAG_SEARCH 等业务类型 */
    private String bizType;

    /** 上下文 token 预算，超出即裁剪并标记 degraded */
    private Integer tokenBudget;

    /** 实际打包的 token 估算值 */
    private Integer tokenUsed;

    /** 是否发生降级（预算裁剪 / 语料缺失等） */
    private Boolean degraded;

    private String degradedReason;

    /** 组装后的上下文负载（与发给 agent 的 payload 一致） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> snapshot;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
