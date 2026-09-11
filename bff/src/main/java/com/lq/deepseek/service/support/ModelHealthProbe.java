package com.lq.deepseek.service.support;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 模型端点连通性探测。
 *
 * <p>仅做轻量 HTTP 探针（GET baseUrl），超时短、失败即降级；不依赖具体模型协议，
 * 因此可复用于任意 OpenAI 兼容 / DeepSeek 接入点的可达性校验。网络异常统一归一成
 * {@link ProbeResult#ok=false}，避免把底层异常抛到接口层。
 */
@Component
public class ModelHealthProbe {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    public ProbeResult ping(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return new ProbeResult(true, null, null);
        }
        long started = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .timeout(READ_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            long latencyMs = (System.nanoTime() - started) / 1_000_000;
            return new ProbeResult(response.statusCode() < 500, latencyMs, null);
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - started) / 1_000_000;
            return new ProbeResult(false, latencyMs, e.getMessage());
        }
    }

    /** 探测结果。latencyMs 为 null 表示未真正发起请求（如空地址）。 */
    @Getter
    public static class ProbeResult {
        private final boolean ok;
        private final Long latencyMs;
        private final String error;

        public ProbeResult(boolean ok, Long latencyMs, String error) {
            this.ok = ok;
            this.latencyMs = latencyMs;
            this.error = error;
        }
    }
}
