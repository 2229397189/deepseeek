package com.lq.deepseek.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lq.deepseek.common.BusinessException;
import com.lq.deepseek.common.ErrorCode;
import com.lq.deepseek.domain.entity.DecisionAnalysis;
import com.lq.deepseek.domain.entity.DecisionSession;
import com.lq.deepseek.domain.entity.FileAsset;
import com.lq.deepseek.domain.entity.InterviewReport;
import com.lq.deepseek.domain.entity.InterviewSession;
import com.lq.deepseek.domain.mapper.DecisionAnalysisMapper;
import com.lq.deepseek.domain.mapper.DecisionSessionMapper;
import com.lq.deepseek.domain.mapper.FileAssetMapper;
import com.lq.deepseek.domain.mapper.InterviewReportMapper;
import com.lq.deepseek.domain.mapper.InterviewSessionMapper;
import com.lq.deepseek.dto.GraphDtos;
import com.lq.deepseek.service.GraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识图谱实现。
 *
 * <p>节点 / 边由简历、决策会话、面试记录的真实数据动态推导：候选人居中，
 * 外挂简历 / 求职 / 面试 / 技能 / 项目 / 岗位；薄弱点（决策缺口、面试薄弱项）标记为 WEAK。
 * 图谱在查询时计算，不落持久层，保证随时与源数据一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphServiceImpl implements GraphService {

    private static final String BIZ_RESUME = "RESUME";
    private static final String STATUS_SUCCESS = "SUCCESS";

    private final FileAssetMapper fileAssetMapper;
    private final DecisionSessionMapper sessionMapper;
    private final InterviewSessionMapper interviewSessionMapper;
    private final DecisionAnalysisMapper analysisMapper;
    private final InterviewReportMapper reportMapper;

    @Override
    public List<GraphDtos.GraphNode> nodes(Long userId) {
        return build(userId).nodes;
    }

    @Override
    public List<GraphDtos.GraphEdge> edges(Long userId) {
        return build(userId).edges;
    }

    @Override
    public List<GraphDtos.Evidence> evidence(Long userId, String nodeId) {
        if (!StringUtils.hasText(nodeId)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "缺少节点 ID");
        }
        List<GraphDtos.Evidence> evidence = new ArrayList<>();
        String[] parts = nodeId.split(":", 2);
        String prefix = parts[0];
        String raw = parts.length > 1 ? parts[1] : "";

        if ("resume".equals(prefix)) {
            FileAsset asset = fileAssetMapper.selectById(safeId(raw));
            if (asset != null) {
                evidence.add(ev("简历", "文件名：" + asset.getFileName(), asset.getCreatedAt()));
            }
        } else if ("job".equals(prefix)) {
            DecisionSession session = sessionMapper.selectById(safeId(raw));
            if (session != null) {
                evidence.add(ev("JD 分析", "岗位：" + session.getJobTitle(), session.getCreatedAt()));
            }
        } else if ("interview".equals(prefix)) {
            InterviewSession session = interviewSessionMapper.selectById(safeId(raw));
            InterviewReport report = latestReport(safeId(raw));
            String excerpt = (session == null ? "模拟面试" : session.getTitle());
            if (report != null && report.getWeakPoints() != null && !report.getWeakPoints().isEmpty()) {
                excerpt += "；薄弱点：" + String.join("、", report.getWeakPoints());
            }
            evidence.add(ev("模拟面试", excerpt, session == null ? null : session.getCreatedAt()));
        } else if ("skill".equals(prefix)) {
            evidence.add(ev("能力评估", "标签：" + raw, null));
        } else if ("project".equals(prefix)) {
            evidence.add(ev("项目经历", "项目：" + raw, null));
        } else if ("candidate".equals(prefix)) {
            evidence.add(ev("候选人", "当前用户的能力 / 求职 / 面试全景", null));
        }
        return evidence;
    }

    // ------------------------------------------------------------------
    // 图谱构建
    // ------------------------------------------------------------------

    private GraphData build(Long userId) {
        List<GraphDtos.GraphNode> nodes = new ArrayList<>();
        List<GraphDtos.GraphEdge> edges = new ArrayList<>();
        Set<String> edgeKeys = new HashSet<>();
        Map<String, Boolean> skillWeak = new LinkedHashMap<>();

        nodes.add(node("candidate", "我", "CANDIDATE", null, 0));

        // 简历 -> 技能 / 项目
        List<FileAsset> resumes = fileAssetMapper.selectList(new LambdaQueryWrapper<FileAsset>()
                .eq(FileAsset::getUserId, userId)
                .eq(FileAsset::getBizType, BIZ_RESUME)
                .eq(FileAsset::getParseStatus, STATUS_SUCCESS)
                .last("LIMIT 50"));
        for (FileAsset asset : resumes) {
            String rid = "resume:" + asset.getId();
            nodes.add(node(rid, asset.getFileName(), "RESUME", null, 1));
            addEdge(edges, edgeKeys, "candidate", rid, "拥有");
            for (String skill : strListOf(asset.getParseResult().get("skills"))) {
                String sid = registerSkill(skillWeak, skill, false);
                addEdge(edges, edgeKeys, rid, sid, "掌握");
            }
            List<?> projects = listOf(asset.getParseResult().get("projects"));
            for (int i = 0; i < projects.size(); i++) {
                String pid = "project:" + asset.getId() + ":" + i;
                nodes.add(node(pid, String.valueOf(projects.get(i)), "PROJECT", null, 2));
                addEdge(edges, edgeKeys, rid, pid, "包含");
            }
        }

        // 决策会话 -> 岗位 -> 技能（要求）
        List<DecisionSession> sessions = sessionMapper.selectList(new LambdaQueryWrapper<DecisionSession>()
                .eq(DecisionSession::getUserId, userId)
                .last("LIMIT 50"));
        for (DecisionSession session : sessions) {
            if (!StringUtils.hasText(session.getJobTitle())) {
                continue;
            }
            String jid = "job:" + session.getId();
            nodes.add(node(jid, session.getJobTitle(), "JOB", null, 1));
            addEdge(edges, edgeKeys, "candidate", jid, "应聘");

            DecisionAnalysis analysis = latestAnalysis(session.getId());
            if (analysis != null) {
                for (String skill : nullSafe(analysis.getRequiredSkills())) {
                    String sid = registerSkill(skillWeak, skill, false);
                    addEdge(edges, edgeKeys, jid, sid, "要求");
                }
                for (String skill : nullSafe(analysis.getMissingSkills())) {
                    String sid = registerSkill(skillWeak, skill, true);
                    addEdge(edges, edgeKeys, jid, sid, "要求");
                }
            }
        }

        // 面试 -> 薄弱点追问
        List<InterviewSession> interviews = interviewSessionMapper.selectList(new LambdaQueryWrapper<InterviewSession>()
                .eq(InterviewSession::getUserId, userId)
                .last("LIMIT 50"));
        for (InterviewSession iv : interviews) {
            String iid = "interview:" + iv.getId();
            InterviewReport report = latestReport(iv.getId());
            boolean weak = report != null && report.getWeakPoints() != null && !report.getWeakPoints().isEmpty();
            nodes.add(node(iid, StringUtils.hasText(iv.getTitle()) ? iv.getTitle() : "模拟面试",
                    "INTERVIEW", weak ? "WEAK" : "NORMAL", 2));
            addEdge(edges, edgeKeys, "candidate", iid, "参加");
            if (report != null) {
                for (String weakPoint : nullSafe(report.getWeakPoints())) {
                    String sid = registerSkill(weakPoint, true);
                    addEdge(edges, edgeKeys, iid, sid, "追问");
                }
            }
        }

        // 统一回写技能节点（Weak 优先）
        for (Map.Entry<String, Boolean> entry : skillWeak.entrySet()) {
            String tag = entry.getKey();
            nodes.add(node("skill:" + tag, tag, "SKILL", entry.getValue() ? "WEAK" : "NORMAL", 2));
        }
        return new GraphData(nodes, edges);
    }

    private String registerSkill(Map<String, Boolean> skillWeak, String skill, boolean weak) {
        if (!StringUtils.hasText(skill)) {
            return null;
        }
        skill = skill.trim();
        skillWeak.merge(skill, weak, (oldWeak, newWeak) -> oldWeak || newWeak);
        return "skill:" + skill;
    }

    private String registerSkill(String skill, boolean weak) {
        return registerSkill(new LinkedHashMap<>(), skill, weak);
    }

    private void addEdge(List<GraphDtos.GraphEdge> edges, Set<String> keys, String source, String target, String relation) {
        if (source == null || target == null) {
            return;
        }
        String key = source + "->" + target + ":" + relation;
        if (!keys.add(key)) {
            return;
        }
        GraphDtos.GraphEdge edge = new GraphDtos.GraphEdge();
        edge.setSource(source);
        edge.setTarget(target);
        edge.setRelation(relation);
        edges.add(edge);
    }

    private GraphDtos.GraphNode node(String id, String label, String type, String status, int layer) {
        GraphDtos.GraphNode n = new GraphDtos.GraphNode();
        n.setId(id);
        n.setLabel(label);
        n.setType(type);
        n.setStatus(status);
        n.setLayer(layer);
        return n;
    }

    private GraphDtos.Evidence ev(String source, String excerpt, java.time.OffsetDateTime createdAt) {
        GraphDtos.Evidence e = new GraphDtos.Evidence();
        e.setSource(source);
        e.setExcerpt(excerpt);
        e.setCreatedAt(createdAt);
        return e;
    }

    private DecisionAnalysis latestAnalysis(Long sessionId) {
        List<DecisionAnalysis> analyses = analysisMapper.selectList(new LambdaQueryWrapper<DecisionAnalysis>()
                .eq(DecisionAnalysis::getSessionId, sessionId)
                .orderByDesc(DecisionAnalysis::getCreatedAt)
                .last("LIMIT 1"));
        return analyses.isEmpty() ? null : analyses.get(0);
    }

    private InterviewReport latestReport(Long sessionId) {
        List<InterviewReport> reports = reportMapper.selectList(new LambdaQueryWrapper<InterviewReport>()
                .eq(InterviewReport::getSessionId, sessionId)
                .orderByDesc(InterviewReport::getCreatedAt)
                .last("LIMIT 1"));
        return reports.isEmpty() ? null : reports.get(0);
    }

    private Long safeId(String raw) {
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> nullSafe(List<String> value) {
        return value == null ? List.of() : value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strListOf(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<?> listOf(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    /** 构建产物：节点 + 边。 */
    private record GraphData(List<GraphDtos.GraphNode> nodes, List<GraphDtos.GraphEdge> edges) {
    }
}
