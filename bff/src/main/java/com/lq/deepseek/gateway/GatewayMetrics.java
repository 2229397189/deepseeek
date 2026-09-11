package com.lq.deepseek.gateway;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 治理层运行指标（进程内累计值）。
 *
 * <p>这里的核心指标不是"调用了多少次"，而是"省下了多少次"：
 * {@code replay} 代表命中 single-flight 结果回放、{@code localFallback} 代表 Redis 不可用被迫降级，
 * 两者共同反映治理层的实际收益与风险敞口。
 */
@Component
public class GatewayMetrics {

    private final AtomicLong owner = new AtomicLong();
    private final AtomicLong replay = new AtomicLong();
    private final AtomicLong localFallback = new AtomicLong();
    private final AtomicLong retry = new AtomicLong();
    private final AtomicLong failure = new AtomicLong();
    private final AtomicLong settleFailure = new AtomicLong();

    public void markOwner() {
        owner.incrementAndGet();
    }

    public void markReplay() {
        replay.incrementAndGet();
    }

    public void markLocalFallback() {
        localFallback.incrementAndGet();
    }

    public void markRetry() {
        retry.incrementAndGet();
    }

    public void markFailure() {
        failure.incrementAndGet();
    }

    public void markSettleFailure() {
        settleFailure.incrementAndGet();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> data = new LinkedHashMap<>();
        data.put("owner", owner.get());
        data.put("replay", replay.get());
        data.put("localFallback", localFallback.get());
        data.put("retry", retry.get());
        data.put("failure", failure.get());
        data.put("settleFailure", settleFailure.get());
        return data;
    }
}
