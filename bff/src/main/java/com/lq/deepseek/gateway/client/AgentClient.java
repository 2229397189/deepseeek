package com.lq.deepseek.gateway.client;

import com.lq.deepseek.gateway.model.AgentInvokeCommand;
import com.lq.deepseek.gateway.model.AgentInvokeResult;
import reactor.core.publisher.Flux;

/**
 * Python Agent 传输层客户端（只负责一次 HTTP 往返，不含治理逻辑）。
 *
 * <p>治理逻辑（single-flight / 重试 / 计量 / 审计）统一收敛在
 * {@link com.lq.deepseek.gateway.AiInvocationGateway}，传输层保持无状态可替换。
 */
public interface AgentClient {

    /** 同步调用，失败抛 {@link com.lq.deepseek.gateway.model.AgentCallException}。 */
    AgentInvokeResult invoke(AgentInvokeCommand command, String runId);

    /** SSE 流式调用，逐条返回 data 载荷。 */
    Flux<String> stream(AgentInvokeCommand command, String runId);
}
