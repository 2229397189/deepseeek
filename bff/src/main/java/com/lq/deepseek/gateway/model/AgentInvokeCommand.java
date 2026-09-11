package com.lq.deepseek.gateway.model;

import lombok.Builder;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次 AI 调用的请求描述（Agent Invocation Gateway 的输入契约）。
 *
 * <p>所有业务模块（简历解析、JD 分析、AI 面试、决策咨询、知识库检索）都必须经由本对象
 * 进入网关，禁止业务代码直接构造 HTTP 请求调用 Python Agent——否则会绕过
 * single-flight 去重、重试、超时、计量与审计。
 */
@Data
@Builder
public class AgentInvokeCommand {

    /** 业务类型，取值与 agent_runs.biz_type 保持一致 */
    private String bizType;

    /** 业务主键（会话 ID / 文档 ID），用于审计串联 */
    private Long bizId;

    /** 发起用户 */
    private Long userId;

    /** 业务阶段（如 JD_MATCH、TECH_QA、SCORE），用于前端进度展示 */
    private String stage;

    /** 规格指纹：模型 / 提示词版本等，参与去重键计算 */
    private String specHash;

    /** 业务负载，序列化后参与输入摘要计算 */
    @Builder.Default
    private Map<String, Object> payload = new LinkedHashMap<>();

    /** 单次调用超时（含单次尝试），默认 60s */
    @Builder.Default
    private long timeoutMs = 60_000L;

    /** 预授权冻结额度；>0 时先冻结后结算，避免并发把额度耗尽 */
    @Builder.Default
    private long expectedCredit = 0L;

    /** 显式幂等键；不传时由「业务类型 + 输入摘要」派生 */
    private String idempotencyKey;

    /** 是否允许结果回放（相同输入直接复用上一次结果） */
    @Builder.Default
    private boolean replayEnabled = true;
}
