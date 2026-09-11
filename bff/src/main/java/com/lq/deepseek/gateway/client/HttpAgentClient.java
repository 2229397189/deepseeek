package com.lq.deepseek.gateway.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lq.deepseek.gateway.model.AgentCallException;
import com.lq.deepseek.gateway.model.AgentInvokeCommand;
import com.lq.deepseek.gateway.model.AgentInvokeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * 基于 WebClient 的 Agent 传输实现。
 *
 * <p>错误码归一化：把 HTTP 语义映射成下游无关的稳定错误码
 * （TIMEOUT / CONNECT_ERROR / RATE_LIMIT / UPSTREAM_5XX / UPSTREAM_4XX），
 * 使重试策略无需关心具体传输实现。
 */
@Slf4j
@Component
public class HttpAgentClient implements AgentClient {

    private static final String PATH_INVOKE = "/v1/agent/invoke";
    private static final String PATH_STREAM = "/v1/agent/stream";

    private final WebClient agentWebClient;
    private final ObjectMapper objectMapper;

    public HttpAgentClient(WebClient agentWebClient, ObjectMapper objectMapper) {
        this.agentWebClient = agentWebClient;
        this.objectMapper = objectMapper;
    }

    @SuppressWarnings("unchecked")
    @Override
    public AgentInvokeResult invoke(AgentInvokeCommand command, String runId) {
        long timeoutMs = command.getTimeoutMs() > 0 ? command.getTimeoutMs() : 60_000L;
        long start = System.currentTimeMillis();
        Map<String, Object> body = buildBody(command, runId);
        try {
            Map<String, Object> resp = agentWebClient.post()
                    .uri(PATH_INVOKE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(text -> toException(r.statusCode(), text)))
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .block();
            AgentInvokeResult result = parse(runId, resp);
            result.setLatencyMs(System.currentTimeMillis() - start);
            return result;
        } catch (AgentCallException e) {
            throw e;
        } catch (WebClientRequestException e) {
            throw new AgentCallException("CONNECT_ERROR", "无法连接 Agent 服务：" + rootMessage(e), e);
        } catch (RuntimeException e) {
            // Reactor 的 timeout 把受检 TimeoutException 包在 ReactiveException 里抛出，
            // 因此只能在异常链上识别，不能在 catch 子句里直接捕获 TimeoutException
            if (rootCause(e) instanceof TimeoutException) {
                throw new AgentCallException("TIMEOUT", "Agent 调用超过 " + timeoutMs + "ms 未返回", e);
            }
            log.warn("Agent 调用出现未归类异常 runId={}", runId, e);
            throw new AgentCallException("UPSTREAM_ERROR", rootMessage(e), e);
        }
    }

    @Override
    public Flux<String> stream(AgentInvokeCommand command, String runId) {
        long timeoutMs = command.getTimeoutMs() > 0 ? command.getTimeoutMs() : 600_000L;
        return agentWebClient.post()
                .uri(PATH_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(buildBody(command, runId))
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(text -> toException(r.statusCode(), text)))
                .bodyToFlux(String.class)
                .timeout(Duration.ofMillis(timeoutMs))
                .onErrorMap(TimeoutException.class,
                        e -> new AgentCallException("TIMEOUT", "流式调用超时", e));
    }

    private Map<String, Object> buildBody(AgentInvokeCommand command, String runId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId);
        body.put("bizType", command.getBizType());
        body.put("bizId", command.getBizId());
        body.put("userId", command.getUserId());
        body.put("stage", command.getStage());
        body.put("specHash", command.getSpecHash());
        body.put("payload", command.getPayload() == null ? Collections.emptyMap() : command.getPayload());
        return body;
    }

    @SuppressWarnings("unchecked")
    private AgentInvokeResult parse(String runId, Map<String, Object> resp) {
        assertSuccess(resp);
        String status = asString(resp.get("status"), "SUCCEEDED");
        Object output = resp.get("output");
        Map<String, Object> outputMap = output instanceof Map ? (Map<String, Object>) output
                : new LinkedHashMap<>();
        return AgentInvokeResult.builder()
                .runId(runId)
                .status(status)
                .output(outputMap)
                .errorCode(asString(resp.get("errorCode"), null))
                .errorMsg(asString(resp.get("errorMsg"), null))
                .promptTokens(asInt(resp.get("promptTokens")))
                .outputTokens(asInt(resp.get("outputTokens")))
                .costCredit(asLong(resp.get("costCredit")))
                .attempt(1)
                .build();
    }

    /**
     * 校验响应体是否为成功语义，失败时抛出可重试/不可重试分类的异常。
     *
     * <p>agent-service 的失败语义是 HTTP 200 + status=FAILED + errorCode。
     * 必须在这里转成异常，否则失败会被当作成功落库并结算——用户既看到"评估成功"，
     * 又被扣了额度，且重试策略完全失效（HTTP 层面一切正常，重试根本不会触发）。
     */
    static void assertSuccess(Map<String, Object> resp) {
        if (resp == null) {
            throw new AgentCallException("UPSTREAM_EMPTY", "Agent 返回空响应体");
        }
        String status = asString(resp.get("status"), "SUCCEEDED");
        if (!"SUCCEEDED".equalsIgnoreCase(status)) {
            String code = asString(resp.get("errorCode"), null);
            String msg = asString(resp.get("errorMsg"), null);
            throw new AgentCallException(
                    code == null || code.isBlank() ? "UPSTREAM_ERROR" : code,
                    msg == null || msg.isBlank() ? "Agent 返回失败状态：" + status : msg);
        }
        if (resp.get("output") == null) {
            throw new AgentCallException("UPSTREAM_EMPTY", "Agent 未返回 output");
        }
    }

    private AgentCallException toException(HttpStatusCode status, String body) {
        int value = status.value();
        String code;
        if (value == 429) {
            code = "RATE_LIMIT";
        } else if (value >= 500) {
            code = "UPSTREAM_5XX";
        } else {
            code = "UPSTREAM_4XX";
        }
        return new AgentCallException(code, "Agent 返回 HTTP " + value + "：" + abbreviate(body));
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    private Throwable rootCause(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? e.getClass().getSimpleName() : cur.getMessage();
    }

    private static String asString(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private int asInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
