package com.lq.deepseek.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 简历中心 DTO 容器。
 */
public final class ResumeDtos {

    private ResumeDtos() {
    }

    /** 上传 / 查询结果。deduplicated=true 表示命中同一份简历，未重复调用 AI。 */
    @Data
    public static class ResumeVO {
        private Long assetId;
        private String fileName;
        private Long sizeBytes;
        private String sha256;
        /** PENDING / PARSING / SUCCESS / FAILED */
        private String parseStatus;
        private String errorMsg;
        private Boolean deduplicated;
        private Map<String, Object> profile;
        /** Markdown 正文。 */
        private String body;
        private OffsetDateTime createdAt;
    }

    /** 列表项：只回传首屏需要的轻量字段，画像正文按需再取。 */
    @Data
    public static class AssetBrief {
        private Long assetId;
        private String fileName;
        private String parseStatus;
        private Integer completeness;
        private Integer skillCount;
        private Integer experienceYears;
        private OffsetDateTime updatedAt;
    }

    /** 简历润色 / 定制生成请求。 */
    @Data
    public static class PolishRequest {

        private Long assetId;

        @NotBlank(message = "请先选中需要处理的内容")
        private String selectedText;

        /** POLISH=选中文本润色，其它值按"按岗位定制生成"处理 */
        private String mode = "POLISH";

        private String instruction;

        private String jobTitle;

        private String jdText;
    }

    /** 润色结果。original 与 polished 成对返回，前端做左右对照。 */
    @Data
    public static class PolishVO {
        private String runId;
        private String mode;
        private String target;
        private String original;
        private String polished;
        private List<String> reasons;
        private List<String> matchedSkills;
        private Long costCredit;
        /** OWNER / REPLAY，命中回放说明未重复消耗额度 */
        private String flightMode;
    }

    /** 保存简历正文请求。 */
    @Data
    public static class SaveBodyRequest {
        @NotBlank(message = "正文内容不能为空")
        private String body;
    }

    /** 保存正文返回。 */
    @Data
    public static class SaveBodyResult {
        private Long assetId;
        private String message;
    }
}
