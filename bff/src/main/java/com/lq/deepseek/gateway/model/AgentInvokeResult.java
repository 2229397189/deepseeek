package com.lq.deepseek.gateway.model;

import lombok.Builder;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次 AI 调用的结果（含计量数据）。
 *
 * <p>{@link #flightMode} 用于区分本次结果来自真实执行、single-flight 结果回放，
 * 这是可观测性的关键字段：命中回放说明省下了一次下游推理开销。
 */
@Data
@Builder
public class AgentInvokeResult {

    private String runId;

    /** SUCCEEDED / FAILED / CANCELED */
    private String status;

    @Builder.Default
    private Map<String, Object> output = new LinkedHashMap<>();

    private String errorCode;

    private String errorMsg;

    private int promptTokens;

    private int outputTokens;

    /** 实际消耗额度（credit） */
    private long costCredit;

    private long latencyMs;

    /** 真实尝试次数（重试累加） */
    private int attempt;

    /** OWNER=真实执行, REPLAY=命中回放, LOCAL_OWNER/LOCAL_REPLAY=Redis 降级路径 */
    private String flightMode;

    public boolean succeeded() {
        return "SUCCEEDED".equals(status);
    }
}
