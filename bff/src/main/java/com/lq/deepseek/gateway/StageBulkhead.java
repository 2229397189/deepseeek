package com.lq.deepseek.gateway;

import com.lq.deepseek.common.BusinessException;
import com.lq.deepseek.common.ErrorCode;
import com.lq.deepseek.config.props.LqProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * stage 级并发隔离（舱壁模式）。
 *
 * <p>每个 {@code bizType:stage} 一块独立"舱壁"（信号量）：某个阶段下游变慢时，
 * 最多占满自己的许可数，不会把整个网关的线程/连接拖垮殃及其它阶段。
 * 许可等待有上限，饱和时快速失败（AGENT_UNAVAILABLE），让上层重试或提示用户，
 * 而不是无限排队堆积。
 */
@Slf4j
@Component
public class StageBulkhead {

    private final ConcurrentHashMap<String, Semaphore> bulkheads = new ConcurrentHashMap<>();

    public <T> T run(String stageKey, LqProperties.Gateway.StagePolicy policy, Supplier<T> action) {
        Semaphore permits = bulkheads.computeIfAbsent(stageKey,
                k -> new Semaphore(Math.max(policy.getMaxConcurrent(), 1)));
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(Math.max(policy.getAcquireTimeoutMs(), 0), TimeUnit.MILLISECONDS);
            if (!acquired) {
                log.warn("stage 并发已饱和，快速拒绝 stageKey={} maxConcurrent={}",
                        stageKey, policy.getMaxConcurrent());
                throw new BusinessException(ErrorCode.AGENT_UNAVAILABLE, "当前阶段调用并发已满，请稍后重试");
            }
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.AGENT_STAGE_FAILED, "并发许可等待被中断");
        } finally {
            if (acquired) {
                permits.release();
            }
        }
    }
}
