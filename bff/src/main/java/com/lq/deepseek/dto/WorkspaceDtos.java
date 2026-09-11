package com.lq.deepseek.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 工作台 DTO 容器。
 */
public final class WorkspaceDtos {

    private WorkspaceDtos() {
    }

    /** 意图识别请求。 */
    @Data
    public static class IntentRequest {

        @NotBlank(message = "请输入内容")
        private String text;
    }

    /** 意图识别结果。 */
    @Data
    public static class IntentResult {
        /** JD / RESUME / INTERVIEW / UNKNOWN */
        private String intent;
        private String targetRoute;
        private List<String> suggestions;
    }
}
