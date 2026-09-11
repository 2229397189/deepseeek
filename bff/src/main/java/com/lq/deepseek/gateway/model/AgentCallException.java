package com.lq.deepseek.gateway.model;

import lombok.Getter;

/**
 * Agent 调用异常。{@link #code} 采用与下游约定的大写错误码，
 * 网关据此判定是否可重试（TIMEOUT / RATE_LIMIT / UPSTREAM_5XX / CONNECT_ERROR 可重试）。
 */
@Getter
public class AgentCallException extends RuntimeException {

    private final String code;

    public AgentCallException(String code, String message) {
        super(message);
        this.code = code;
    }

    public AgentCallException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
