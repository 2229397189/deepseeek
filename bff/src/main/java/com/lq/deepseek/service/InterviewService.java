package com.lq.deepseek.service;

import com.lq.deepseek.dto.InterviewDtos;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * AI 模拟面试服务。
 */
public interface InterviewService {

    /** 开始一场面试，生成首题并落库会话。 */
    InterviewDtos.InterviewSession start(Long userId, InterviewDtos.StartRequest request);

    /** 当前用户的面试会话列表。 */
    List<InterviewDtos.InterviewSession> listSessions(Long userId);

    /** 会话详情（消息历史 + 终态报告）。 */
    InterviewDtos.InterviewSessionDetail detail(Long userId, Long sessionId);

    /** 提交一次作答，驱动下一道题生成并持久化消息。 */
    InterviewDtos.InterviewTurn answer(Long userId, Long sessionId, InterviewDtos.AnswerRequest request);

    /** 结束面试并生成终态报告。 */
    InterviewDtos.InterviewReport finish(Long userId, Long sessionId);

    /** 读取面试报告（幂等：已结束直接返回既有报告）。 */
    InterviewDtos.InterviewReport report(Long userId, Long sessionId);

    /** 语音转写（ASR；无配置时返回优雅降级占位文本）。 */
    InterviewDtos.TranscribeVO transcribe(Long userId, InterviewDtos.TranscribeRequest request);
}
