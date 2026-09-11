package com.lq.deepseek.gateway.model;

import java.util.Map;

/**
 * single-flight 共享的结果快照。
 *
 * <p>owner 执行完成后把结果序列化成 JSON 写入 Redis，follower 与后续同输入请求直接回放该快照——
 * 因此它必须只包含「可跨请求复用」的字段，不含连接、线程等上下文。
 *
 * <p>做成 record 是为了让 Jackson 走构造器反序列化，避免依赖 setter 与无参构造。
 */
public record AgentRunSnapshot(
        String status,
        Map<String, Object> output,
        String errorCode,
        String errorMsg,
        int promptTokens,
        int outputTokens,
        long costCredit,
        int latencyMs,
        int attempt) {

    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    public static AgentRunSnapshot from(AgentInvokeResult result) {
        return new AgentRunSnapshot(
                result.getStatus(),
                result.getOutput(),
                result.getErrorCode(),
                result.getErrorMsg(),
                result.getPromptTokens(),
                result.getOutputTokens(),
                result.getCostCredit(),
                (int) result.getLatencyMs(),
                result.getAttempt());
    }

    public boolean succeeded() {
        return STATUS_SUCCEEDED.equals(status);
    }
}
