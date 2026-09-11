package com.lq.deepseek.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 调用治理层（Agent Invocation Gateway）DTO 容器。
 */
public final class GatewayDtos {

    private GatewayDtos() {
    }

    /**
     * 调用请求。业务侧只需要声明"谁来调 / 调什么业务 / 负载是什么"，
     * 去重、重试、计量、结算、审计全部由网关承担。
     */
    @Data
    public static class InvokeRequest {

        @NotBlank(message = "业务类型不能为空")
        private String bizType;

        private Long bizId;

        private String stage;

        /** 规格指纹：模型 / 提示词版本，参与去重键计算；不传表示使用默认规格 */
        private String specHash;

        private Map<String, Object> payload = new LinkedHashMap<>();

        /** 单次调用超时，缺省 60s */
        private Long timeoutMs;

        /** 预授权冻结额度，>0 时先冻结后结算 */
        private Long expectedCredit;

        private Boolean replayEnabled;
    }

    @Data
    @Builder
    public static class InvokeVO {
        private String runId;
        private String status;
        /** OWNER=真实执行 REPLAY=命中回放 LOCAL_OWNER/LOCAL_REPLAY=Redis 降级路径 */
        private String flightMode;
        private Map<String, Object> output;
        private String errorCode;
        private String errorMsg;
        private Integer promptTokens;
        private Integer outputTokens;
        private Long costCredit;
        private Long latencyMs;
        private Integer attempt;
    }

    @Data
    @Builder
    public static class RunVO {
        private String runId;
        private String bizType;
        private Long bizId;
        private String stage;
        private String status;
        private String specHash;
        private String inputDigest;
        private Integer attempt;
        private String ownerInstance;
        private String errorCode;
        private String errorMsg;
        private Integer promptTokens;
        private Integer outputTokens;
        private Long costCredit;
        private Integer latencyMs;
        private OffsetDateTime startedAt;
        private OffsetDateTime finishedAt;
        private OffsetDateTime createdAt;
    }

    @Data
    @Builder
    public static class RunPage {
        private long total;
        private long pageNum;
        private long pageSize;
        private List<RunVO> records;
    }

    /**
     * 治理指标：replay 越高说明去重收益越大，localFallback 非 0 说明 Redis 曾抖动。
     */
    @Data
    @Builder
    public static class MetricsVO {
        private Long owner;
        private Long replay;
        private Long localFallback;
        private Long retry;
        private Long failure;
        private Long settleFailure;
    }
}
