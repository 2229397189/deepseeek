package com.lq.deepseek.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lq.deepseek.common.BusinessException;
import com.lq.deepseek.common.ErrorCode;
import com.lq.deepseek.dto.GatewayDtos;
import com.lq.deepseek.gateway.AiInvocationGateway;
import com.lq.deepseek.gateway.model.AgentInvokeCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.Map;

/**
 * SSE 透传入口：把前端的 Agent 流式调用转交治理层。
 *
 * <p>用户身份由 BFF 从 Sa-Token 注入（严禁由前端携带 userId，否则可冒充他人调用），
 * runId 与审计由 {@link AiInvocationGateway} 生成，业务侧只声明业务类型与负载。
 */
@Slf4j
@Tag(name = "Agent 流式调用")
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentStreamController {

    private final AiInvocationGateway gateway;

    /**
     * 流式调用（text/event-stream）。事件形态由 agent-service 定义：step / result。
     */
    @Operation(summary = "SSE 流式调用（step / result 事件）")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@Valid @RequestBody GatewayDtos.AgentStreamRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        if (userId == null) {
            // 全局拦截器已保证登录态，此处兜底防止绕过
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        Map<String, Object> payload = request.getPayload() == null
                ? Collections.emptyMap() : request.getPayload();

        AgentInvokeCommand command = AgentInvokeCommand.builder()
                .bizType(request.getBizType())
                .bizId(request.getBizId())
                .userId(userId)
                .stage(request.getStage())
                .specHash(request.getSpecHash())
                .payload(payload)
                .build();
        return gateway.stream(command);
    }
}
