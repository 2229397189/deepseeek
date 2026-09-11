package com.lq.deepseek.gateway.singleflight;

import com.lq.deepseek.config.props.LqProperties;
import com.lq.deepseek.gateway.GatewayMetrics;
import com.lq.deepseek.gateway.model.AgentCallException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 分布式 single-flight 编排器。
 *
 * <p>解决的问题：同一个 Agent 调用（相同用户 + 相同业务类型 + 相同规格 + 相同输入）在并发场景下
 * 会被重复打到下游大模型，既放大成本也放大延迟。这里让"第一个到的"成为 owner 真实执行，
 * 其余请求作为 follower 挂在同一 key 上等结果回放，从而把 N 次推理压成 1 次。
 *
 * <p>状态机（Redis Hash，key = lq:sf:{bizType}:{digest}）：
 * <pre>
 *   ownerId / ownerExpireAt   owner 租约，心跳续租；租约过期允许被接管（防 owner 进程崩溃后死锁）
 *   status                    RUNNING -> DONE / FAILED
 *   result                    终态结果 JSON，保留 replayTtl 供后续同输入请求回放
 *   followers                 当前 follower 计数，超过 maxFollowers 快速失败以保护下游
 * </pre>
 *
 * <p>Redis 不可用时降级为进程内 {@link LocalFlightRegistry}：单实例语义仍然成立，
 * 但多实例部署下退化为"每实例一次调用"，因此这是保可用性的兜底而非等价方案。
 */
@Slf4j
@Component
public class SingleFlightCoordinator {

    private static final String KEY_PREFIX = "lq:sf:";
    private static final long POLL_INTERVAL_MS = 50L;
    /** 失败态只短暂保留：让等待者立刻失败，同时不阻塞下一次立即重试 */
    private static final long FAILED_STATE_TTL_MS = 5_000L;

    private final StringRedisTemplate redis;
    private final LocalFlightRegistry localRegistry;
    private final LqProperties properties;
    private final GatewayMetrics metrics;
    private final DefaultRedisScript<Long> acquireScript;
    private final DefaultRedisScript<Long> joinScript;
    private final DefaultRedisScript<Long> renewScript;
    private final DefaultRedisScript<Long> publishScript;
    private final ScheduledExecutorService heartbeatPool;
    private final String instanceId;

    public SingleFlightCoordinator(StringRedisTemplate redis,
                                   LocalFlightRegistry localRegistry,
                                   LqProperties properties,
                                   GatewayMetrics metrics,
                                   @Qualifier("singleFlightAcquireScript") DefaultRedisScript<Long> acquireScript,
                                   @Qualifier("singleFlightJoinScript") DefaultRedisScript<Long> joinScript,
                                   @Qualifier("singleFlightRenewScript") DefaultRedisScript<Long> renewScript,
                                   @Qualifier("singleFlightPublishScript") DefaultRedisScript<Long> publishScript) {
        this.redis = redis;
        this.localRegistry = localRegistry;
        this.properties = properties;
        this.metrics = metrics;
        this.acquireScript = acquireScript;
        this.joinScript = joinScript;
        this.renewScript = renewScript;
        this.publishScript = publishScript;
        this.instanceId = buildInstanceId();
        this.heartbeatPool = Executors.newScheduledThreadPool(2, new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "sf-heartbeat-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    /** 当前实例标识，用于审计"这次调用由哪个实例真实执行"。 */
    public String instanceId() {
        return instanceId;
    }

    /**
     * 以 single-flight 语义执行一次计算。
     *
     * @param flightKey     业务维度去重键（不含 Redis 前缀）
     * @param waitTimeoutMs 作为 follower 时的最长等待时间
     * @param producer      真实执行逻辑，返回可回放的结果 JSON
     */
    public FlightOutcome run(String flightKey, long waitTimeoutMs, Supplier<String> producer) {
        LqProperties.Gateway.SingleFlight cfg = properties.getGateway().getSingleFlight();
        String key = KEY_PREFIX + flightKey;
        try {
            return runDistributed(key, waitTimeoutMs, producer, cfg);
        } catch (FlightStateUnavailableException e) {
            if (!cfg.isLocalFallbackEnabled()) {
                throw new AgentCallException("UPSTREAM_ERROR", "single-flight 状态存储不可用：" + e.getMessage(), e);
            }
            metrics.markLocalFallback();
            log.warn("single-flight 降级为进程内实现 key={}，原因={}", flightKey, e.getMessage());
            long wait = waitTimeoutMs > 0 ? Math.min(waitTimeoutMs, cfg.getMaxWaitMs()) : cfg.getMaxWaitMs();
            return localRegistry.run(flightKey, producer, wait);
        }
    }

    private FlightOutcome runDistributed(String key, long waitTimeoutMs, Supplier<String> producer,
                                         LqProperties.Gateway.SingleFlight cfg) {
        long now = System.currentTimeMillis();
        Long acquired = execute(acquireScript, List.of(key),
                instanceId, String.valueOf(cfg.getOwnerLeaseMs()), String.valueOf(now));
        if (acquired != null && acquired == 1L) {
            return runAsOwner(key, producer, cfg);
        }
        return runAsFollower(key, waitTimeoutMs, cfg, now);
    }

    private FlightOutcome runAsOwner(String key, Supplier<String> producer, LqProperties.Gateway.SingleFlight cfg) {
        long start = System.currentTimeMillis();
        metrics.markOwner();
        // 心跳续租：producer 执行时间可能远超租约，续租失败（被接管）只告警，不打断当前执行
        ScheduledFuture<?> heartbeat = heartbeatPool.scheduleAtFixedRate(
                () -> renew(key, cfg.getOwnerLeaseMs()),
                cfg.getHeartbeatIntervalMs(), cfg.getHeartbeatIntervalMs(), TimeUnit.MILLISECONDS);
        try {
            String payload = producer.get();
            publish(key, "DONE", payload, null, null, cfg.getReplayTtlMs());
            return new FlightOutcome(FlightOutcome.MODE_OWNER, payload, System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            publish(key, "FAILED", null, resolveCode(e), e.getMessage(), FAILED_STATE_TTL_MS);
            throw e;
        } finally {
            heartbeat.cancel(false);
        }
    }

    private FlightOutcome runAsFollower(String key, long waitTimeoutMs,
                                        LqProperties.Gateway.SingleFlight cfg, long start) {
        Long joined = execute(joinScript, List.of(key), String.valueOf(cfg.getMaxFollowers()));
        if (joined == null || joined == 0L) {
            throw new AgentCallException("SINGLE_FLIGHT_REJECTED",
                    "相同请求的并发等待数已达上限 " + cfg.getMaxFollowers() + "，已快速失败以保护下游");
        }
        long budget = waitTimeoutMs > 0 ? Math.min(waitTimeoutMs, cfg.getMaxWaitMs()) : cfg.getMaxWaitMs();
        long deadline = System.currentTimeMillis() + budget;
        while (true) {
            Map<Object, Object> state = readState(key);
            String status = asString(state.get("status"));
            if ("DONE".equals(status)) {
                metrics.markReplay();
                return new FlightOutcome(FlightOutcome.MODE_REPLAY, asString(state.get("result")),
                        System.currentTimeMillis() - start);
            }
            if ("FAILED".equals(status)) {
                throw new AgentCallException(
                        defaultIfBlank(asString(state.get("errorCode")), "UPSTREAM_ERROR"),
                        "并发执行的同一请求失败：" + defaultIfBlank(asString(state.get("errorMsg")), "未知原因"));
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new AgentCallException("TIMEOUT", "等待同一请求的执行结果超时（" + budget + "ms）");
            }
            sleep(POLL_INTERVAL_MS);
        }
    }

    private void renew(String key, long leaseMs) {
        try {
            Long renewed = redis.execute(renewScript, List.of(key), instanceId,
                    String.valueOf(leaseMs), String.valueOf(System.currentTimeMillis()));
            if (renewed == null || renewed == 0L) {
                log.warn("single-flight 续租失败，owner 身份可能已被接管 key={}", key);
            }
        } catch (RuntimeException e) {
            log.warn("single-flight 续租异常 key={}，原因={}", key, e.getMessage());
        }
    }

    private void publish(String key, String status, String payload, String errorCode, String errorMsg, long ttlMs) {
        try {
            redis.execute(publishScript, List.of(key), status,
                    payload == null ? "" : payload,
                    errorCode == null ? "" : errorCode,
                    errorMsg == null ? "" : errorMsg,
                    String.valueOf(Math.max(ttlMs, 1_000L)),
                    String.valueOf(System.currentTimeMillis()));
        } catch (RuntimeException e) {
            // 结果发布失败只影响回放能力，不影响本次调用结果，因此仅告警
            log.warn("single-flight 结果发布失败 key={}，本次调用不受影响，原因={}", key, e.getMessage());
        }
    }

    private Map<Object, Object> readState(String key) {
        try {
            return redis.opsForHash().entries(key);
        } catch (RuntimeException e) {
            throw new FlightStateUnavailableException(e.getMessage(), e);
        }
    }

    private Long execute(DefaultRedisScript<Long> script, List<String> keys, Object... args) {
        try {
            return redis.execute(script, keys, args);
        } catch (RuntimeException e) {
            throw new FlightStateUnavailableException(e.getMessage(), e);
        }
    }

    private static String resolveCode(Throwable e) {
        if (e instanceof AgentCallException ace) {
            return ace.getCode();
        }
        return "UPSTREAM_ERROR";
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentCallException("INTERRUPTED", "等待同一请求结果时被中断", e);
        }
    }

    private static String buildInstanceId() {
        try {
            return ManagementFactory.getRuntimeMXBean().getName();
        } catch (RuntimeException e) {
            return "bff-unknown";
        }
    }

    /** Redis 状态读写不可用信号，由外层决定是否降级到进程内实现。 */
    private static final class FlightStateUnavailableException extends RuntimeException {
        private FlightStateUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
