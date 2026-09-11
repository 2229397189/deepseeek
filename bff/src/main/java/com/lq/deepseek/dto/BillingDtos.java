package com.lq.deepseek.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 计费与额度 DTO 容器。
 */
public final class BillingDtos {

    private BillingDtos() {
    }

    @Data
    @Builder
    public static class WalletVO {
        private Long userId;
        /** 可用额度（余额 - 冻结） */
        private Long availableCredit;
        private Long balanceCredit;
        private Long frozenCredit;
        private Long totalGranted;
        private Long totalConsumed;
    }

    @Data
    @Builder
    public static class LedgerVO {
        private Long id;
        private String changeType;
        private Long amount;
        private Long balanceAfter;
        private String bizType;
        private Long bizId;
        private String runId;
        private String remark;
        private OffsetDateTime createdAt;
    }

    @Data
    @Builder
    public static class LedgerPage {
        private long total;
        private long pageNum;
        private long pageSize;
        private List<LedgerVO> records;
    }

    @Data
    public static class RechargeRequest {

        @NotNull(message = "充值额度不能为空")
        @Positive(message = "充值额度必须大于 0")
        private Long amount;

        /** 幂等键，客户端生成，服务端唯一索引兜底 */
        private String idempotencyKey;

        private String remark;
    }

    /**
     * 预授权冻结请求：AI 调用前冻结，调用结束后按实际消耗结算。
     */
    @Data
    public static class FreezeRequest {

        @NotNull(message = "冻结额度不能为空")
        @Positive(message = "冻结额度必须大于 0")
        private Long amount;

        private String bizType;

        private Long bizId;
    }
}
