package com.lq.deepseek.gateway.client;

import com.lq.deepseek.gateway.model.AgentCallException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 校验 agent 响应体的失败语义转换。
 *
 * <p>这组用例守的是一个很容易被忽略、但代价很高的坑：agent-service 业务失败走的是
 * HTTP 200 + status=FAILED。如果网关只看 HTTP 状态码，失败会被当成成功——
 * 用户看到"评估成功"却已被扣费，而且重试策略永远不触发。
 */
class HttpAgentClientStatusTest {

    @Test
    @DisplayName("status=FAILED 必须转成异常，并携带 agent 的原始错误码")
    void failedStatusBecomesRetryableException() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "FAILED");
        resp.put("errorCode", "RATE_LIMIT");
        resp.put("errorMsg", "模型侧限流");

        AgentCallException ex = assertThrows(AgentCallException.class,
                () -> HttpAgentClient.assertSuccess(resp));
        assertEquals("RATE_LIMIT", ex.getCode());
        assertEquals("模型侧限流", ex.getMessage());
    }

    @Test
    @DisplayName("缺少错误码时回落到 UPSTREAM_ERROR，而不是抛空指针")
    void failedStatusWithoutCodeFallsBack() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "FAILED");

        AgentCallException ex = assertThrows(AgentCallException.class,
                () -> HttpAgentClient.assertSuccess(resp));
        assertEquals("UPSTREAM_ERROR", ex.getCode());
    }

    @Test
    @DisplayName("成功但 output 缺失视为空响应，不能当成有效结果落库")
    void succeededWithoutOutputIsEmptyError() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "SUCCEEDED");

        AgentCallException ex = assertThrows(AgentCallException.class,
                () -> HttpAgentClient.assertSuccess(resp));
        assertEquals("UPSTREAM_EMPTY", ex.getCode());
    }

    @Test
    @DisplayName("成功响应正常放行")
    void succeededWithOutputPasses() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "SUCCEEDED");
        resp.put("output", Map.of("score", 88));

        assertDoesNotThrow(() -> HttpAgentClient.assertSuccess(resp));
    }
}
