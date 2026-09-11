package com.lq.deepseek.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * JD 分析（Decision）DTO 容器。
 */
public final class DecisionDtos {

    private DecisionDtos() {
    }

    /** 快速预览请求：只回答"我够不够格"，不做完整评估。 */
    @Data
    public static class PreviewRequest {

        private Long resumeAssetId;

        private Long jdAssetId;

        /** 直接粘贴的 JD 文本，与 jdAssetId 二选一 */
        private String jdText;
    }

    /** 正式分析请求：sessionId 为空表示新建会话。 */
    @Data
    public static class AnalyzeRequest {

        private Long sessionId;

        private Long resumeAssetId;

        private Long jdAssetId;

        private String jdText;

        private String jobTitle;

        private String title;
    }

    @Data
    public static class AskRequest {

        @NotBlank(message = "请输入要追问的问题")
        private String question;
    }

    /** JD 上传结果：解析失败也要能看到原因，因此 parseStatus/errorMsg 一并返回。 */
    @Data
    public static class JdUploadVO {
        private Long assetId;
        private String fileName;
        private String sha256;
        private String parseStatus;
        private String errorMsg;
        private Boolean deduplicated;
        private Integer charCount;
        private String preview;
        private List<String> hardRequirements;
        private List<String> skills;
    }

    @Data
    public static class PreviewVO {
        private String runId;
        private Integer score;
        private String scoreBand;
        private String conclusion;
        private List<String> coveredSkills;
        private List<String> missingSkills;
        private List<String> weakPointsHit;
        private List<String> risks;
        private String advice;
        private Boolean preview;
        private List<Map<String, Object>> steps;
        private Long costCredit;
        /** OWNER / REPLAY：命中回放说明未重复消耗额度 */
        private String flightMode;
    }

    @Data
    public static class AnalysisVO {
        private Long analysisId;
        private Long sessionId;
        private String runId;
        private String mode;
        private Integer score;
        private String scoreBand;
        private String conclusion;
        private Map<String, Object> dimensions;
        private List<String> requiredSkills;
        private List<String> coveredSkills;
        private List<String> missingSkills;
        private List<String> weakPointsHit;
        private List<String> hardRequirements;
        private List<String> risks;
        private String advice;
        private List<String> todos;
        private List<Map<String, Object>> steps;
        private Long costCredit;
        private String flightMode;
        private OffsetDateTime createdAt;
    }

    @Data
    public static class SessionBrief {
        private Long sessionId;
        private String title;
        private String jobTitle;
        private String status;
        private Integer latestScore;
        private Long latestAnalysisId;
        private Integer analysisCount;
        private OffsetDateTime updatedAt;
    }

    @Data
    public static class MessageVO {
        private Integer seq;
        private String role;
        private String content;
        private OffsetDateTime createdAt;
    }

    /** 会话详情：analyze / detail 共用同一份结构，前端只维护一套渲染逻辑。 */
    @Data
    public static class SessionDetailVO {
        private Long sessionId;
        private String title;
        private String jobTitle;
        private String status;
        private Integer latestScore;
        private Long resumeAssetId;
        private Long jdAssetId;
        private Integer jdCharCount;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
        /** 最新一次分析（列表首屏直接用这一条） */
        private AnalysisVO latestAnalysis;
        /** 历史分析（含 latestAnalysis 本身，按时间倒序） */
        private List<AnalysisVO> analyses;
        private List<MessageVO> messages;
        private Boolean degraded;
        private String degradedReason;
    }

    @Data
    public static class AskVO {
        private String runId;
        private String answer;
        private List<Map<String, Object>> hits;
        private Boolean recallGap;
        private List<MessageVO> messages;
        private Long costCredit;
        private String flightMode;
    }
}
