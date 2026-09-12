package com.lq.deepseek.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * AI 模拟面试 DTO 容器。
 */
public final class InterviewDtos {

    private InterviewDtos() {
    }

    /** 开始一场面试。 */
    @Data
    public static class StartRequest {

        /** 关联简历资产（驱动出题方向），可空 */
        private Long resumeAssetId;

        /** 目标岗位，可空；空时由 agent 按简历推断 */
        private String jobTitle;

        /** 指定模型（来自 /models/available 的 id），可空 */
        private String model;
    }

    /** 作答请求。 */
    @Data
    public static class AnswerRequest {

        @NotBlank(message = "请输入你的回答")
        private String content;
    }

    /** 会话概览（列表 / 开始返回）。 */
    @Data
    public static class InterviewSession {
        private Long sessionId;
        private String jobTitle;
        private String status;
        private OffsetDateTime createdAt;
    }

    /** 一条消息（与前端 MessageVO 形态对齐）。 */
    @Data
    public static class MessageVO {
        private Integer seq;
        /** INTERVIEWER / CANDIDATE / SYSTEM */
        private String role;
        private String content;
        private OffsetDateTime createdAt;
        /** 该题得分（仅 AI 出题消息或带回评估的用户作答消息携带） */
        private Integer questionScore;
        /** 命中关键词 */
        private List<String> hitKeywords;
        /** 缺失关键词 */
        private List<String> missedKeywords;
        /** 是否为追问 */
        private Boolean isFollowUp;
        /** 题号 */
        private Integer questionIndex;
    }

    /** 会话详情（含消息历史 + 终态报告）。 */
    @Data
    public static class InterviewSessionDetail {
        private Long sessionId;
        private String status;
        private Integer score;
        private List<MessageVO> messages;
        private InterviewReport report;
    }

    /** 一次作答的回包：用户消息 + 下一道题（AI 消息）。transcript 为可选语音转写回显，暂留空。 */
    @Data
    public static class InterviewTurn {
        private MessageVO userMessage;
        private MessageVO aiMessage;
        private Object transcript;
    }

    /** 面试报告。 */
    @Data
    public static class InterviewReport {
        private Long sessionId;
        private Integer score;
        private Map<String, Object> dimensions;
        private List<String> weakPoints;
        private String suggestion;
        private OffsetDateTime createdAt;
    }

    /** 语音转写请求（ASR，见待明确 §8-3）。 */
    @Data
    public static class TranscribeRequest {
        /** base64 编码的音频 */
        private String audio;
        private String format;
    }

    /** 语音转写结果。无 ASR 配置时返回优雅降级占位文本。 */
    @Data
    public static class TranscribeVO {
        private String text;
    }
}
