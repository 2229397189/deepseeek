package com.lq.deepseek.gateway;

import com.lq.deepseek.config.props.LqProperties;
import org.springframework.stereotype.Component;

/**
 * stage 级治理策略解析。
 *
 * <p>查找顺序：{@code bizType:stage}（最具体）→ {@code bizType} → 全局默认。
 * 让"面试 START 慢一些、重试多一些，RAG 检索快失败"这类 stage 差异化策略
 * 只需要写配置，不需要改任何业务代码。
 */
@Component
public class StagePolicyRegistry {

    private final LqProperties properties;

    public StagePolicyRegistry(LqProperties properties) {
        this.properties = properties;
    }

    public LqProperties.Gateway.StagePolicy resolve(String bizType, String stage) {
        var stages = properties.getGateway().getStages();
        if (stage != null) {
            var exact = stages.get(bizType + ":" + stage);
            if (exact != null) {
                return exact;
            }
        }
        var byBiz = stages.get(bizType);
        if (byBiz != null) {
            return byBiz;
        }
        return properties.getGateway().getDefaultStage();
    }

    /** 生效超时：调用方显式指定 > stage 策略 > 兜底 120s。 */
    public long effectiveTimeoutMs(Long commandTimeoutMs, LqProperties.Gateway.StagePolicy policy) {
        if (commandTimeoutMs != null && commandTimeoutMs > 0) {
            return commandTimeoutMs;
        }
        if (policy.getTimeoutMs() != null && policy.getTimeoutMs() > 0) {
            return policy.getTimeoutMs();
        }
        return 120_000L;
    }

    /** 生效重试次数：stage 策略覆盖 > 全局 Retry。 */
    public int effectiveMaxAttempts(LqProperties.Gateway.StagePolicy policy) {
        if (policy.getMaxAttempts() != null && policy.getMaxAttempts() > 0) {
            return policy.getMaxAttempts();
        }
        return Math.max(properties.getGateway().getRetry().getMaxAttempts(), 1);
    }
}
