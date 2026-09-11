package com.lq.deepseek.gateway.singleflight;

import com.lq.deepseek.gateway.model.AgentCallException;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 进程内 single-flight（Redis 不可用时的降级路径）。
 *
 * <p>语义与分布式版本对齐：同一 key 只允许一个 owner 真正执行，其余参与者等待同一结果。
 * 区别在于作用域退化为单实例，多实例部署时会放大下游流量——因此这属于「保可用性」的兜底，
 * 一旦 Redis 恢复即自动回到分布式路径（见 {@link SingleFlightCoordinator}）。
 */
@Slf4j
public class LocalFlightRegistry {

    private final ConcurrentHashMap<String, CompletableFuture<String>> flights = new ConcurrentHashMap<>();

    public FlightOutcome run(String key, Supplier<String> producer, long maxWaitMs) {
        long start = System.currentTimeMillis();
        CompletableFuture<String> mine = new CompletableFuture<>();
        CompletableFuture<String> exists = flights.putIfAbsent(key, mine);

        if (exists == null) {
            try {
                String payload = producer.get();
                mine.complete(payload);
                return new FlightOutcome(FlightOutcome.MODE_LOCAL_OWNER, payload,
                        System.currentTimeMillis() - start);
            } catch (RuntimeException e) {
                mine.completeExceptionally(e);
                throw e;
            } finally {
                flights.remove(key, mine);
            }
        }

        try {
            String payload = exists.get(Math.max(maxWaitMs, 1L), TimeUnit.MILLISECONDS);
            return new FlightOutcome(FlightOutcome.MODE_LOCAL_REPLAY, payload,
                    System.currentTimeMillis() - start);
        } catch (TimeoutException e) {
            throw new AgentCallException("TIMEOUT", "等待同一请求的执行结果超时（本地降级模式）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentCallException("INTERRUPTED", "等待过程中被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new AgentCallException("UPSTREAM_ERROR", String.valueOf(cause), cause);
        }
    }

    /** 当前在途的 flight 数量，供监控与自检使用。 */
    public int inFlight() {
        return flights.size();
    }
}
