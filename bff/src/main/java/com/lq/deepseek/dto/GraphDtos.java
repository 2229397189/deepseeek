package com.lq.deepseek.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 知识图谱 DTO 容器。
 */
public final class GraphDtos {

    private GraphDtos() {
    }

    /** 图谱节点。id 为业务键（如 resume:123 / skill:Java）。 */
    @Data
    public static class GraphNode {
        private String id;
        private String label;
        /** CANDIDATE / RESUME / JOB / INTERVIEW / SKILL / PROJECT / POSITION */
        private String type;
        /** WEAK / NORMAL，可能为空 */
        private String status;
        private Integer layer;
    }

    /** 图谱边。source / target 引用 GraphNode.id。 */
    @Data
    public static class GraphEdge {
        private String source;
        private String target;
        private String relation;
    }

    /** 节点证据（点击节点抽屉展示）。 */
    @Data
    public static class Evidence {
        private String source;
        private String excerpt;
        private OffsetDateTime createdAt;
    }

    /** 图谱整体（节点 + 边一次返回，前端力导向渲染）。 */
    @Data
    public static class Graph {
        private List<GraphNode> nodes;
        private List<GraphEdge> edges;
    }
}
