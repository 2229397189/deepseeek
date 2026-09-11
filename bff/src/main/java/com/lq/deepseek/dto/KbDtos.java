package com.lq.deepseek.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 知识库 DTO 容器。
 */
public final class KbDtos {

    private KbDtos() {
    }

    /** 知识库文档概览。 */
    @Data
    public static class KbDocument {
        private Long documentId;
        private String title;
        private Integer chunkCount;
        private OffsetDateTime updatedAt;
    }

    /** 检索命中。vectorScore / ftsScore 同时回填，前端可做 RRF 融合展示。 */
    @Data
    public static class KbHit {
        private Long documentId;
        private String title;
        private String content;
        private Double score;
        private Double vectorScore;
        private Double ftsScore;
    }

    /** 重建索引回包。 */
    @Data
    public static class KbReindexResult {
        private Boolean accepted;
    }
}
