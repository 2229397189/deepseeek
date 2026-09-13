package com.lq.deepseek.config.props;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台自定义配置。对应 application.yml 中的 lq.* 前缀。
 */
@Data
@ConfigurationProperties(prefix = "lq")
public class LqProperties {

    private Agent agent = new Agent();
    private Gateway gateway = new Gateway();
    private Storage storage = new Storage();
    private Kb kb = new Kb();

    /** 知识库检索配置（语料级 RetrievalProfile 的全局默认值） */
    @Data
    public static class Kb {
        private Retrieval retrieval = new Retrieval();

        /**
         * 混合检索融合参数：多路召回（FTS / trigram 相似度 / 向量）按 RRF 融合，
         * score = Σ w_i / (rrfK + rank_i)。向量路在 pgvector 与 embedding 数据就绪时启用，
         * 未就绪时该路权重不参与融合（结果标记 degraded 原因）。
         */
        @Data
        public static class Retrieval {
            /** PostgreSQL FTS（tsv @@ tsquery 的 ts_rank）权重 */
            private double wFts = 1.0;
            /** pg_trgm 相似度权重 */
            private double wTrgm = 0.8;
            /** pgvector 向量近邻权重（向量路未启用时不参与） */
            private double wVector = 1.2;
            /** RRF 常数 K，抑制头部排名的过度影响 */
            private int rrfK = 60;
            /** trigram 相似度低于该值的候选不进入融合 */
            private double minTrgm = 0.02;
        }
    }

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
        /** stage 级策略：key = bizType 或 bizType:stage（如 DECIDE、INTERVIEW:FINISH），未命中走 defaultStage */
        private Map<String, StagePolicy> stages = new LinkedHashMap<>();
        private StagePolicy defaultStage = new StagePolicy();

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

        /**
         * 单个 stage 的治理策略：超时 / 重试次数 / 并发隔离（舱壁）。
         *
         * <p>timeoutMs 与 maxAttempts 为空表示沿用全局 Retry 与调用方传入的超时。
         */
        @Data
        public static class StagePolicy {
            private Long timeoutMs;
            private Integer maxAttempts;
            /** 并发隔离信号量许可数（该 stage 同时真实打到下游的最大调用量） */
            private int maxConcurrent = 8;
            /** 获取并发许可的最长等待，超时快速失败而非无限排队 */
            private long acquireTimeoutMs = 2_000L;
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
