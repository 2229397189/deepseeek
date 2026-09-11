package com.lq.deepseek.config.props;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 平台自定义配置。对应 application.yml 中的 lq.* 前缀。
 */
@Data
@ConfigurationProperties(prefix = "lq")
public class LqProperties {

    private Agent agent = new Agent();
    private Gateway gateway = new Gateway();
    private Storage storage = new Storage();

    /** Python Agent 服务接入参数 */
    @Data
    public static class Agent {
        private String baseUrl = "http://127.0.0.1:8000";
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration readTimeout = Duration.ofSeconds(60);
        /** SSE 流式读超时 */
        private Duration streamTimeout = Duration.ofMinutes(10);
    }

    /** 统一 AI Invocation Gateway 治理参数 */
    @Data
    public static class Gateway {
        private SingleFlight singleFlight = new SingleFlight();
        private Retry retry = new Retry();

        /** 分布式 Single-flight 策略 */
        @Data
        public static class SingleFlight {
            /** owner 租约时长，超时视为持有者失联 */
            private long ownerLeaseMs = 30_000L;
            /** 心跳续租间隔，必须显著小于租约时长 */
            private long heartbeatIntervalMs = 5_000L;
            /** follower 最长等待时间 */
            private long maxWaitMs = 120_000L;
            /** 单 key 允许的 follower 上限，超过直接拒绝，保护下游 */
            private int maxFollowers = 200;
            /** 结果回放缓存时长 */
            private long replayTtlMs = 300_000L;
            /** Redis 不可用时是否降级为本地单机 single-flight */
            private boolean localFallbackEnabled = true;
        }

        /** 重试策略 */
        @Data
        public static class Retry {
            private int maxAttempts = 3;
            private long backoffMs = 300L;
            /** 仅对这些错误码重试 */
            private String retryableCodes = "TIMEOUT,RATE_LIMIT,UPSTREAM_5XX,CONNECT_ERROR";
        }
    }

    /** 文件存储 */
    @Data
    public static class Storage {
        private String type = "local";
        private String localRoot = "./data/files";
        private long maxFileSize = 20 * 1024 * 1024L;
        private String allowedExtensions = "pdf,doc,docx,md,txt,png,jpg,jpeg,webp";
    }
}
