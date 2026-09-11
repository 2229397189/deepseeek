package com.lq.deepseek.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 用户画像 / 长期记忆 DTO 容器。
 */
public final class ProfileDtos {

    private ProfileDtos() {
    }

    /** 能力标签（聚合自简历技能 + 决策分析 + 面试报告）。 */
    @Data
    public static class CapabilityTag {
        private String tag;
        /** SKILL / STRENGTH / GAP / WEAK */
        private String category;
        /** 掌握层级，可能为空 */
        private String level;
        /** RESUME / DECISION / INTERVIEW */
        private String source;
        private Double confidence;
    }

    /** 长期记忆条目。 */
    @Data
    public static class LongTermMemory {
        private Long id;
        private String content;
        /** PENDING / CONFIRMED / CORRECTED */
        private String status;
        private OffsetDateTime createdAt;
    }

    /** 修正记忆请求。 */
    @Data
    public static class MemoryCorrectRequest {

        @jakarta.validation.constraints.NotBlank(message = "修正内容不能为空")
        private String correctedContent;
    }
}
