package com.lq.deepseek.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lq.deepseek.common.BusinessException;
import com.lq.deepseek.common.ErrorCode;
import com.lq.deepseek.common.Result;
import com.lq.deepseek.domain.entity.AgentRun;
import com.lq.deepseek.domain.mapper.AgentRunMapper;
import com.lq.deepseek.dto.GatewayDtos;
import com.lq.deepseek.gateway.AiInvocationGateway;
import com.lq.deepseek.gateway.GatewayMetrics;
import com.lq.deepseek.gateway.model.AgentInvokeCommand;
import com.lq.deepseek.gateway.model.AgentInvokeResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * AI 调用治理层入口。
 *
 * <p>业务模块统一走 {@code POST /gateway/invoke}，不得绕过网关直连 Python Agent；
 * 另外暴露运行记录与治理指标，便于前端"调用治理"面板展示去重收益与失败分布。
 */
@RestController
@RequestMapping("/gateway")
public class GatewayController {

    private static final long MAX_PAGE_SIZE = 100L;

    private final AiInvocationGateway gateway;
    private final AgentRunMapper runMapper;
    private final GatewayMetrics metrics;

    public GatewayController(AiInvocationGateway gateway, AgentRunMapper runMapper, GatewayMetrics metrics) {
        this.gateway = gateway;
        this.runMapper = runMapper;
        this.metrics = metrics;
    }

    /**
     * 发起一次受治理的 AI 调用。
     */
    @PostMapping("/invoke")
    public Result<GatewayDtos.InvokeVO> invoke(@Valid @RequestBody GatewayDtos.InvokeRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        AgentInvokeCommand command = AgentInvokeCommand.builder()
                .bizType(request.getBizType())
                .bizId(request.getBizId())
                .userId(userId)
                .stage(request.getStage())
                .specHash(request.getSpecHash())
                .payload(request.getPayload() == null ? Map.of() : request.getPayload())
                .timeoutMs(request.getTimeoutMs() == null ? 60_000L : request.getTimeoutMs())
                .expectedCredit(request.getExpectedCredit() == null ? 0L : request.getExpectedCredit())
                .replayEnabled(request.getReplayEnabled() == null || request.getReplayEnabled())
                .build();
        AgentInvokeResult result = gateway.invoke(command);
        return Result.ok(GatewayDtos.InvokeVO.builder()
                .runId(result.getRunId())
                .status(result.getStatus())
                .flightMode(result.getFlightMode())
                .output(result.getOutput())
                .errorCode(result.getErrorCode())
                .errorMsg(result.getErrorMsg())
                .promptTokens(result.getPromptTokens())
                .outputTokens(result.getOutputTokens())
                .costCredit(result.getCostCredit())
                .latencyMs(result.getLatencyMs())
                .attempt(result.getAttempt())
                .build());
    }

    /**
     * 当前用户的调用记录分页。
     */
    @GetMapping("/runs")
    public Result<GatewayDtos.RunPage> runs(@RequestParam(defaultValue = "1") long pageNum,
                                            @RequestParam(defaultValue = "20") long pageSize,
                                            @RequestParam(required = false) String bizType,
                                            @RequestParam(required = false) String status) {
        Long userId = StpUtil.getLoginIdAsLong();
        long safeSize = Math.min(Math.max(pageSize, 1L), MAX_PAGE_SIZE);
        LambdaQueryWrapper<AgentRun> wrapper = new LambdaQueryWrapper<AgentRun>()
                .eq(AgentRun::getUserId, userId)
                .eq(bizType != null && !bizType.isBlank(), AgentRun::getBizType, bizType)
                .eq(status != null && !status.isBlank(), AgentRun::getStatus, status)
                .orderByDesc(AgentRun::getCreatedAt);
        Page<AgentRun> page = runMapper.selectPage(new Page<>(Math.max(pageNum, 1L), safeSize), wrapper);
        List<GatewayDtos.RunVO> records = page.getRecords().stream().map(this::toRunVO).toList();
        return Result.ok(GatewayDtos.RunPage.builder()
                .total(page.getTotal())
                .pageNum(page.getCurrent())
                .pageSize(page.getSize())
                .records(records)
                .build());
    }

    /**
     * 单次调用详情（含输入摘要，便于定位同一输入的重复调用）。
     */
    @GetMapping("/runs/{runId}")
    public Result<GatewayDtos.RunVO> run(@PathVariable String runId) {
        Long userId = StpUtil.getLoginIdAsLong();
        AgentRun run = runMapper.selectByRunId(runId);
        if (run == null || !userId.equals(run.getUserId())) {
            throw new BusinessException(ErrorCode.RUN_NOT_FOUND);
        }
        return Result.ok(toRunVO(run));
    }

    /**
     * 治理指标快照。
     */
    @GetMapping("/metrics")
    public Result<GatewayDtos.MetricsVO> metrics() {
        Map<String, Long> snapshot = metrics.snapshot();
        return Result.ok(GatewayDtos.MetricsVO.builder()
                .owner(snapshot.get("owner"))
                .replay(snapshot.get("replay"))
                .localFallback(snapshot.get("localFallback"))
                .retry(snapshot.get("retry"))
                .failure(snapshot.get("failure"))
                .settleFailure(snapshot.get("settleFailure"))
                .build());
    }

    private GatewayDtos.RunVO toRunVO(AgentRun run) {
        return GatewayDtos.RunVO.builder()
                .runId(run.getRunId())
                .bizType(run.getBizType())
                .bizId(run.getBizId())
                .stage(run.getStage())
                .status(run.getStatus())
                .specHash(run.getSpecHash())
                .inputDigest(run.getInputDigest())
                .attempt(run.getAttempt())
                .ownerInstance(run.getOwnerInstance())
                .errorCode(run.getErrorCode())
                .errorMsg(run.getErrorMsg())
                .promptTokens(run.getPromptTokens())
                .outputTokens(run.getOutputTokens())
                .costCredit(run.getCostCredit())
                .latencyMs(run.getLatencyMs())
                .startedAt(run.getStartedAt())
                .finishedAt(run.getFinishedAt())
                .createdAt(run.getCreatedAt())
                .build();
    }
}
