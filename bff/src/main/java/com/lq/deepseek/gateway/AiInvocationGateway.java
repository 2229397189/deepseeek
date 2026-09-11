package com.lq.deepseek.gateway;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lq.deepseek.common.BusinessException;
import com.lq.deepseek.common.ErrorCode;
import com.lq.deepseek.config.props.LqProperties;
import com.lq.deepseek.domain.entity.AgentRun;
import com.lq.deepseek.domain.mapper.AgentRunMapper;
import com.lq.deepseek.gateway.client.AgentClient;
import com.lq.deepseek.gateway.model.AgentCallException;
import com.lq.deepseek.gateway.model.AgentInvokeCommand;
import com.lq.deepseek.gateway.model.AgentInvokeResult;
import com.lq.deepseek.gateway.model.AgentRunSnapshot;
import com.lq.deepseek.gateway.singleflight.FlightOutcome;
import com.lq.deepseek.gateway.singleflight.SingleFlightCoordinator;
import com.lq.deepseek.gateway.support.InvokeDigest;
import com.lq.deepseek.service.BillingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 统一 AI Invocation Gateway。
 *
 * <p>所有业务模块调用 Python Agent 的唯一入口，把六件事收敛到一处，业务代码不再各自实现：
 * <ol>
 *   <li><b>去重</b>：按输入摘要做分布式 single-flight，相同输入并发只真实调用下游一次；</li>
 *   <li><b>重试</b>：仅对可重试错误码（超时 / 限流 / 5xx / 连接失败）做退避重试；</li>
 *   <li><b>计量</b>：token 用量换算 credit，支持下游直接回传成本；</li>
 *   <li><b>结算</b>：调用前预授权冻结，调用后按实际消耗结算，回放请求不计费；</li>
 *   <li><b>审计</b>：每次请求（含回放）落 agent_runs，串起"同一输入的多条调用链"；</li>
 *   <li><b>可观测</b>：owner / replay / 降级 / 重试 / 失败指标全部可查。</li>
 * </ol>
 */
@Slf4j
@Component
public class AiInvocationGateway {

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";

    /** 与 agent_runs.ck_run_biz 约束一致，提前校验避免撞库层异常 */
    private static final Set<String> ALLOWED_BIZ_TYPES = Set.of(
            "DECIDE", "INTERVIEW", "RESUME_PARSE", "RESUME_QUESTION", "DECIDE_PREVIEW", "RAG_SEARCH");

    private final AgentClient agentClient;
    private final SingleFlightCoordinator coordinator;
    private final AiCostCalculator costCalculator;
    private final BillingService billingService;
    private final AgentRunMapper runMapper;
    private final ObjectMapper objectMapper;
    private final LqProperties properties;
    private final GatewayMetrics metrics;

    public AiInvocationGateway(AgentClient agentClient,
                               SingleFlightCoordinator coordinator,
                               AiCostCalculator costCalculator,
                               BillingService billingService,
                               AgentRunMapper runMapper,
                               ObjectMapper objectMapper,
                               LqProperties properties,
                               GatewayMetrics metrics) {
        this.agentClient = agentClient;
        this.coordinator = coordinator;
        this.costCalculator = costCalculator;
        this.billingService = billingService;
        this.runMapper = runMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 同步调用：去重 -> 重试执行 -> 计量结算 -> 审计，异常统一转成业务异常。
     */
    public AgentInvokeResult invoke(AgentInvokeCommand command) {
        validate(command);
        long startedAt = System.currentTimeMillis();
        String runId = "run_" + IdWorker.getIdStr();
        String digest = InvokeDigest.of(objectMapper, command.getBizType(), spec(command), digestPayload(command));
        String flightKey = command.getBizType() + ":" + digest;

        AgentRun run = newRun(command, runId, digest);
        runMapper.insert(run);

        Long freezeLedgerId = freezeIfNeeded(command, runId);

        FlightOutcome outcome;
        try {
            outcome = coordinator.run(flightKey, command.getTimeoutMs(), () -> executeWithRetry(command, runId));
        } catch (AgentCallException e) {
            releaseFreeze(command, freezeLedgerId, runId, "调用失败释放冻结");
            finishFailed(runId, e.getCode(), e.getMessage(), elapsed(startedAt));
            throw mapToBusiness(e);
        } catch (BusinessException e) {
            releaseFreeze(command, freezeLedgerId, runId, "调用异常释放冻结");
            finishFailed(runId, e.getErrorCode().name(), e.getMessage(), elapsed(startedAt));
            throw e;
        }

        AgentRunSnapshot snapshot = parseSnapshot(outcome.payloadJson());
        boolean owner = outcome.isOwner();
        long cost = owner
                ? costCalculator.cost(snapshot.promptTokens(), snapshot.outputTokens(), snapshot.costCredit())
                : 0L;
        settle(command, freezeLedgerId, cost, runId, owner);
        finishSucceeded(runId, snapshot, cost, outcome, elapsed(startedAt));
        return toResult(runId, snapshot, cost, outcome);
    }

    /**
     * 流式调用。
     *
     * <p>SSE 按连接逐帧推送，天然无法在多请求间共享，因此不进 single-flight；
     * 但同样落审计与计量，保证"绕过治理层直接调下游"这条路不存在。
     */
    public Flux<String> stream(AgentInvokeCommand command) {
        validate(command);
        long startedAt = System.currentTimeMillis();
        String runId = "run_" + IdWorker.getIdStr();
        String digest = InvokeDigest.of(objectMapper, command.getBizType(), spec(command), digestPayload(command));
        runMapper.insert(newRun(command, runId, digest));

        return agentClient.stream(command, runId)
                .doOnComplete(() -> {
                    metrics.markOwner();
                    finishSucceeded(runId,
                            new AgentRunSnapshot(STATUS_SUCCEEDED, null, null, null, 0, 0, 0L, 0, 1),
                            0L, null, elapsed(startedAt));
                })
                .doOnError(error -> {
                    metrics.markFailure();
                    finishFailed(runId, codeOf(error), error.getMessage(), elapsed(startedAt));
                });
    }

    // ------------------------------------------------------------------
    // 执行与重试
    // ------------------------------------------------------------------

    private String executeWithRetry(AgentInvokeCommand command, String runId) {
        LqProperties.Gateway.Retry retry = properties.getGateway().getRetry();
        Set<String> retryableCodes = Arrays.stream(retry.getRetryableCodes().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        int maxAttempts = Math.max(retry.getMaxAttempts(), 1);
        long start = System.currentTimeMillis();
        AgentCallException last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                AgentInvokeResult result = agentClient.invoke(command, runId);
                result.setAttempt(attempt);
                if (result.getLatencyMs() <= 0) {
                    result.setLatencyMs(System.currentTimeMillis() - start);
                }
                return toJson(AgentRunSnapshot.from(result));
            } catch (AgentCallException e) {
                last = e;
                boolean retryable = attempt < maxAttempts && retryableCodes.contains(e.getCode());
                log.warn("Agent 调用失败（第 {}/{} 次）runId={} code={} 可重试={} 原因={}",
                        attempt, maxAttempts, runId, e.getCode(), retryable, e.getMessage());
                if (!retryable) {
                    break;
                }
                metrics.markRetry();
                sleep(retry.getBackoffMs() * attempt);
            }
        }
        metrics.markFailure();
        throw last;
    }

    private AgentRunSnapshot parseSnapshot(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new BusinessException(ErrorCode.AGENT_STAGE_FAILED, "回放结果为空");
        }
        try {
            return objectMapper.readValue(payloadJson, AgentRunSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.AGENT_STAGE_FAILED, "回放结果解析失败", e);
        }
    }

    // ------------------------------------------------------------------
    // 计费
    // ------------------------------------------------------------------

    private Long freezeIfNeeded(AgentInvokeCommand command, String runId) {
        if (command.getExpectedCredit() <= 0) {
            return null;
        }
        return billingService.freeze(command.getUserId(), command.getExpectedCredit(),
                command.getBizType(), command.getBizId(), "freeze:" + runId);
    }

    private void settle(AgentInvokeCommand command, Long freezeLedgerId, long cost, String runId, boolean owner) {
        if (!owner) {
            // 结果回放没有产生新的下游推理开销，因此不计费：冻结原路释放
            releaseFreeze(command, freezeLedgerId, runId, "命中结果回放释放冻结");
            return;
        }
        try {
            if (freezeLedgerId != null) {
                billingService.settle(command.getUserId(), freezeLedgerId, command.getExpectedCredit(), cost,
                        command.getBizType(), command.getBizId(), runId, "settle:" + runId, "AI 调用结算 " + runId);
            } else if (cost > 0) {
                billingService.deduct(command.getUserId(), cost, command.getBizType(), command.getBizId(), runId,
                        "deduct:" + runId, "AI 调用扣费 " + runId);
            }
        } catch (BusinessException e) {
            // 内容已经产出，此时让用户看不到结果是更差的体验；差额由 credit_ledger 与
            // agent_runs.cost_credit 对账发现，交后台补扣，因此这里只告警不抛出。
            log.error("AI 调用结算失败待对账 runId={} cost={} 原因={}", runId, cost, e.getMessage());
            metrics.markSettleFailure();
        }
    }

    private void releaseFreeze(AgentInvokeCommand command, Long freezeLedgerId, String runId, String remark) {
        if (freezeLedgerId == null || command.getExpectedCredit() <= 0) {
            return;
        }
        try {
            billingService.unfreeze(command.getUserId(), command.getExpectedCredit(),
                    command.getBizType(), command.getBizId(), "unfreeze:" + runId, remark + " " + runId);
        } catch (BusinessException e) {
            log.error("释放冻结失败待对账 runId={} amount={} 原因={}", runId, command.getExpectedCredit(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 审计
    // ------------------------------------------------------------------

    private AgentRun newRun(AgentInvokeCommand command, String runId, String digest) {
        AgentRun run = new AgentRun();
        run.setRunId(runId);
        run.setUserId(command.getUserId());
        run.setBizType(command.getBizType());
        run.setBizId(command.getBizId());
        run.setStage(command.getStage());
        run.setStatus(STATUS_RUNNING);
        run.setSpecHash(spec(command));
        run.setInputDigest(digest);
        run.setAttempt(0);
        run.setOwnerInstance(coordinator.instanceId());
        run.setPromptTokens(0);
        run.setOutputTokens(0);
        run.setCostCredit(0L);
        run.setLatencyMs(0);
        run.setStartedAt(OffsetDateTime.now());
        return run;
    }

    private void finishSucceeded(String runId, AgentRunSnapshot snapshot, long cost,
                                 FlightOutcome outcome, int latencyMs) {
        runMapper.finish(runId, STATUS_SUCCEEDED, snapshot.attempt(), toJson(snapshot.output()),
                snapshot.errorCode(), snapshot.errorMsg(), snapshot.promptTokens(), snapshot.outputTokens(),
                cost, latencyMs);
        log.info("AI 调用完成 runId={} mode={} tokens={}/{} cost={} latency={}ms",
                runId, outcome == null ? "STREAM" : outcome.mode(), snapshot.promptTokens(),
                snapshot.outputTokens(), cost, latencyMs);
    }

    private void finishFailed(String runId, String errorCode, String errorMsg, int latencyMs) {
        runMapper.finish(runId, STATUS_FAILED, 0, null, errorCode, abbreviate(errorMsg), 0, 0, 0L, latencyMs);
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private void validate(AgentInvokeCommand command) {
        if (command == null || command.getUserId() == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "调用方未指定用户");
        }
        if (!ALLOWED_BIZ_TYPES.contains(command.getBizType())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "不支持的业务类型：" + command.getBizType());
        }
    }

    private String spec(AgentInvokeCommand command) {
        return command.getSpecHash() == null ? "" : command.getSpecHash();
    }

    /**
     * 摘要输入 = 用户 + 阶段 + 业务主键 + 业务负载。
     *
     * <p>必须带上用户：否则两个用户输入相同内容会互相回放彼此的结果，属于越权与串数据。
     */
    private Map<String, Object> digestPayload(AgentInvokeCommand command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", command.getUserId());
        payload.put("stage", command.getStage() == null ? "" : command.getStage());
        payload.put("bizId", command.getBizId());
        if (command.getPayload() != null) {
            payload.putAll(command.getPayload());
        }
        return payload;
    }

    private AgentInvokeResult toResult(String runId, AgentRunSnapshot snapshot, long cost, FlightOutcome outcome) {
        return AgentInvokeResult.builder()
                .runId(runId)
                .status(snapshot.status())
                .output(snapshot.output() == null ? new LinkedHashMap<>() : snapshot.output())
                .errorCode(snapshot.errorCode())
                .errorMsg(snapshot.errorMsg())
                .promptTokens(snapshot.promptTokens())
                .outputTokens(snapshot.outputTokens())
                .costCredit(cost)
                .latencyMs(snapshot.latencyMs())
                .attempt(snapshot.attempt())
                .flightMode(outcome.mode())
                .build();
    }

    private BusinessException mapToBusiness(AgentCallException e) {
        ErrorCode code = switch (e.getCode()) {
            case "TIMEOUT" -> ErrorCode.AGENT_TIMEOUT;
            case "SINGLE_FLIGHT_REJECTED" -> ErrorCode.SINGLE_FLIGHT_REJECTED;
            case "CONNECT_ERROR", "RATE_LIMIT", "UPSTREAM_5XX", "UPSTREAM_ERROR", "UPSTREAM_EMPTY" ->
                    ErrorCode.AGENT_UNAVAILABLE;
            default -> ErrorCode.AGENT_STAGE_FAILED;
        };
        return new BusinessException(code, code.getMessage(), e);
    }

    private String codeOf(Throwable error) {
        return error instanceof AgentCallException ace ? ace.getCode() : "UPSTREAM_ERROR";
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("序列化失败，降级为 null：{}", e.getMessage());
            return null;
        }
    }

    private int elapsed(long startedAt) {
        return (int) Math.min(System.currentTimeMillis() - startedAt, Integer.MAX_VALUE);
    }

    private String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 500 ? text.substring(0, 500) + "..." : text;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.AGENT_STAGE_FAILED, "重试等待被中断", e);
        }
    }
}
