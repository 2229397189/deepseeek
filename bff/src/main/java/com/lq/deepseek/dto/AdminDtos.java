package com.lq.deepseek.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 系统管理（模型配置 / 计费规则）DTO 容器。
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    /** 模型配置（读取时 apiKey 已脱敏）。 */
    @Data
    public static class ModelConfig {
        private Long id;
        private String name;
        private String provider;
        private String baseUrl;
        private String model;
        /** 脱敏后的密钥（读取时仅保留尾部掩码） */
        private String apiKey;
        private Boolean enabled;
        private OffsetDateTime createdAt;
    }

    /** 模型配置创建 / 更新请求。 */
    @Data
    public static class ModelConfigRequest {

        @NotBlank(message = "模型名称不能为空")
        private String name;

        @NotBlank(message = "供应商不能为空")
        private String provider;

        private String baseUrl;

        @NotBlank(message = "模型标识不能为空")
        private String model;

        /** 敏感字段：落库原文，读取时脱敏 */
        private String apiKey;

        private Boolean enabled;
    }

    /** 模型连通性探测结果。 */
    @Data
    public static class ModelTestResult {
        private Boolean ok;
        private Long latencyMs;
        private String error;
    }

    /** 计费规则。 */
    @Data
    public static class PricingRule {
        private String bizType;
        private Long unitCredit;
        private String description;
    }

    /** 模型配置列表 + 计费规则。 */
    @Data
    public static class AdminOverview {
        private List<ModelConfig> models;
        private List<PricingRule> pricing;
    }
}
