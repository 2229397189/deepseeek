package com.lq.deepseek.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lq.deepseek.common.Result;
import com.lq.deepseek.dto.InterviewDtos;
import com.lq.deepseek.service.InterviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 模拟面试入口。
 */
@Tag(name = "AI 模拟面试")
@RestController
@RequestMapping("/interview")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewService interviewService;

    @Operation(summary = "开始一场面试，生成首题")
    @PostMapping("/start")
    public Result<InterviewDtos.InterviewSession> start(@Valid @RequestBody InterviewDtos.StartRequest request) {
        return Result.ok(interviewService.start(StpUtil.getLoginIdAsLong(), request));
    }

    @Operation(summary = "我的面试会话列表")
    @GetMapping("/sessions")
    public Result<List<InterviewDtos.InterviewSession>> sessions() {
        return Result.ok(interviewService.listSessions(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "会话详情（消息历史 + 终态报告）")
    @GetMapping("/sessions/{sessionId}")
    public Result<InterviewDtos.InterviewSessionDetail> detail(@PathVariable Long sessionId) {
        return Result.ok(interviewService.detail(StpUtil.getLoginIdAsLong(), sessionId));
    }

    @Operation(summary = "提交一次作答，驱动下一道题")
    @PostMapping("/sessions/{sessionId}/answer")
    public Result<InterviewDtos.InterviewTurn> answer(@PathVariable Long sessionId,
                                                     @Valid @RequestBody InterviewDtos.AnswerRequest request) {
        return Result.ok(interviewService.answer(StpUtil.getLoginIdAsLong(), sessionId, request));
    }

    @Operation(summary = "结束面试并生成报告")
    @PostMapping("/sessions/{sessionId}/finish")
    public Result<InterviewDtos.InterviewReport> finish(@PathVariable Long sessionId) {
        return Result.ok(interviewService.finish(StpUtil.getLoginIdAsLong(), sessionId));
    }

    @Operation(summary = "NEXT 阶段：依据出题计划推进到下一题")
    @PostMapping("/sessions/{sessionId}/next")
    public Result<InterviewDtos.MessageVO> next(@PathVariable Long sessionId) {
        return Result.ok(interviewService.nextQuestion(StpUtil.getLoginIdAsLong(), sessionId));
    }

    @Operation(summary = "读取面试报告")
    @GetMapping("/sessions/{sessionId}/report")
    public Result<InterviewDtos.InterviewReport> report(@PathVariable Long sessionId) {
        return Result.ok(interviewService.report(StpUtil.getLoginIdAsLong(), sessionId));
    }

    @Operation(summary = "语音转写（ASR）")
    @PostMapping("/transcribe")
    public Result<InterviewDtos.TranscribeVO> transcribe(@Valid @RequestBody InterviewDtos.TranscribeRequest request) {
        return Result.ok(interviewService.transcribe(StpUtil.getLoginIdAsLong(), request));
    }
}
