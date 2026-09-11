package com.lq.deepseek.service;

import com.lq.deepseek.dto.GraphDtos;

import java.util.List;

/**
 * 知识图谱服务（节点 / 边 / 证据动态推导）。
 */
public interface GraphService {

    /** 图谱节点（候选人居中，外挂简历 / 求职 / 面试 / 技能 / 项目 / 岗位）。 */
    List<GraphDtos.GraphNode> nodes(Long userId);

    /** 图谱边（source / target 均引用节点业务键）。 */
    List<GraphDtos.GraphEdge> edges(Long userId);

    /** 单个节点的证据（点击抽屉展示）。 */
    List<GraphDtos.Evidence> evidence(Long userId, String nodeId);
}
