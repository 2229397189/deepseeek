package com.lq.deepseek.service.impl;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 知识图谱行为测试。
 *
 * <p>钉死两条：节点 / 边必须来自真实的简历、决策、面试数据（不能是写死的假数据）；
 * 决策缺口与面试薄弱项对应的技能节点必须标记 WEAK。
 */
class GraphServiceImplTest {

    private static final Long USER_ID = 7L;

    private FileAssetMapper fileAssetMapper;
    private DecisionSessionMapper sessionMapper;
    private InterviewSessionMapper interviewSessionMapper;
    private DecisionAnalysisMapper analysisMapper;
    private InterviewReportMapper reportMapper;
    private GraphServiceImpl service;

    @BeforeEach
    void setUp() {
        fileAssetMapper = mock(FileAssetMapper.class);
        sessionMapper = mock(DecisionSessionMapper.class);
        interviewSessionMapper = mock(InterviewSessionMapper.class);
        analysisMapper = mock(DecisionAnalysisMapper.class);
        reportMapper = mock(InterviewReportMapper.class);
        service = new GraphServiceImpl(fileAssetMapper, sessionMapper, interviewSessionMapper,
                analysisMapper, reportMapper);
    }

    @Test
    void nodes_derivesFromRealResumeDecisionAndInterviewData() {
        when(fileAssetMapper.selectList(any())).thenReturn(List.of(resumeAsset()));
        when(sessionMapper.selectList(any())).thenReturn(List.of(decisionSession()));
        when(analysisMapper.selectList(any())).thenReturn(List.of(analysis()));
        when(interviewSessionMapper.selectList(any())).thenReturn(List.of(interviewSession()));
        when(reportMapper.selectList(any())).thenReturn(List.of(report()));

        List<GraphDtos.GraphNode> nodes = service.nodes(USER_ID);

        assertThat(nodes).anySatisfy(n -> {
            assertThat(n.getType()).isEqualTo("CANDIDATE");
            assertThat(n.getLayer()).isZero();
        });
        assertThat(nodes).anySatisfy(n -> {
            assertThat(n.getType()).isEqualTo("RESUME");
            assertThat(n.getId()).isEqualTo("resume:5001");
        });
        assertThat(nodes).anySatisfy(n -> {
            assertThat(n.getType()).isEqualTo("JOB");
            assertThat(n.getLabel()).isEqualTo("高级 Java 后端工程师");
        });
        assertThat(nodes).anySatisfy(n -> {
            assertThat(n.getType()).isEqualTo("INTERVIEW");
            assertThat(n.getStatus()).isEqualTo("WEAK");
        });
        assertThat(nodes).anySatisfy(n -> {
            assertThat(n.getType()).isEqualTo("PROJECT");
            assertThat(n.getLabel()).isEqualTo("订单中心重构");
        });
        assertThat(nodes).anySatisfy(n -> {
            assertThat(n.getType()).isEqualTo("SKILL");
            assertThat(n.getLabel()).isEqualTo("Java");
        });
    }

    @Test
    void nodes_marksGapAndWeakSkillsAsWeak() {
        when(fileAssetMapper.selectList(any())).thenReturn(List.of(resumeAsset()));
        when(sessionMapper.selectList(any())).thenReturn(List.of(decisionSession()));
        when(analysisMapper.selectList(any())).thenReturn(List.of(analysis()));
        when(interviewSessionMapper.selectList(any())).thenReturn(List.of(interviewSession()));
        when(reportMapper.selectList(any())).thenReturn(List.of(report()));

        List<GraphDtos.GraphNode> nodes = service.nodes(USER_ID);

        // 决策缺口 Kafka、面试薄弱点 JVM 必须标 WEAK；简历掌握项 Java 为 NORMAL
        assertThat(nodes).anySatisfy(n -> {
            assertThat(n.getLabel()).isEqualTo("Kafka");
            assertThat(n.getStatus()).isEqualTo("WEAK");
        });
        assertThat(nodes).anySatisfy(n -> {
            assertThat(n.getLabel()).isEqualTo("JVM");
            assertThat(n.getStatus()).isEqualTo("WEAK");
        });
        assertThat(nodes).anySatisfy(n -> {
            assertThat(n.getLabel()).isEqualTo("Java");
            assertThat(n.getStatus()).isEqualTo("NORMAL");
        });
    }

    @Test
    void edges_connectCandidateToResumeJobAndInterview() {
        when(fileAssetMapper.selectList(any())).thenReturn(List.of(resumeAsset()));
        when(sessionMapper.selectList(any())).thenReturn(List.of(decisionSession()));
        when(analysisMapper.selectList(any())).thenReturn(List.of(analysis()));
        when(interviewSessionMapper.selectList(any())).thenReturn(List.of(interviewSession()));
        when(reportMapper.selectList(any())).thenReturn(List.of(report()));

        List<GraphDtos.GraphEdge> edges = service.edges(USER_ID);

        assertThat(edges).anySatisfy(e -> {
            assertThat(e.getSource()).isEqualTo("candidate");
            assertThat(e.getTarget()).isEqualTo("resume:5001");
            assertThat(e.getRelation()).isEqualTo("拥有");
        });
        assertThat(edges).anySatisfy(e -> {
            assertThat(e.getSource()).isEqualTo("candidate");
            assertThat(e.getTarget()).isEqualTo("job:9001");
            assertThat(e.getRelation()).isEqualTo("应聘");
        });
        assertThat(edges).anySatisfy(e -> {
            assertThat(e.getSource()).isEqualTo("candidate");
            assertThat(e.getTarget()).isEqualTo("interview:8001");
            assertThat(e.getRelation()).isEqualTo("参加");
        });
        assertThat(edges).anySatisfy(e -> {
            assertThat(e.getSource()).isEqualTo("resume:5001");
            assertThat(e.getRelation()).isEqualTo("掌握");
        });
        assertThat(edges).anySatisfy(e -> {
            assertThat(e.getSource()).isEqualTo("interview:8001");
            assertThat(e.getRelation()).isEqualTo("追问");
        });
    }

    @Test
    void evidence_returnsResumeSourceExcerpt() {
        when(fileAssetMapper.selectById(anyLong())).thenReturn(resumeAsset());

        List<GraphDtos.Evidence> evidence = service.evidence(USER_ID, "resume:5001");

        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).getSource()).isEqualTo("简历");
        assertThat(evidence.get(0).getExcerpt()).contains("resume.txt");
    }

    @Test
    void evidence_skillNode_returnsLabelExcerpt() {
        List<GraphDtos.Evidence> evidence = service.evidence(USER_ID, "skill:Java");

        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).getExcerpt()).contains("Java");
    }

    // ------------------------------------------------------------------

    private static FileAsset resumeAsset() {
        FileAsset asset = new FileAsset();
        asset.setId(5001L);
        asset.setUserId(USER_ID);
        asset.setBizType("RESUME");
        asset.setFileName("resume.txt");
        asset.setParseStatus("SUCCESS");
        asset.setParseResult(Map.of(
                "skills", List.of("Java"),
                "projects", List.of("订单中心重构")));
        return asset;
    }

    private static DecisionSession decisionSession() {
        DecisionSession session = new DecisionSession();
        session.setId(9001L);
        session.setUserId(USER_ID);
        session.setJobTitle("高级 Java 后端工程师");
        session.setStatus("FINISHED");
        return session;
    }

    private static DecisionAnalysis analysis() {
        DecisionAnalysis analysis = new DecisionAnalysis();
        analysis.setId(7001L);
        analysis.setSessionId(9001L);
        analysis.setUserId(USER_ID);
        analysis.setRequiredSkills(List.of("Java"));
        analysis.setMissingSkills(List.of("Kafka"));
        return analysis;
    }

    private static InterviewSession interviewSession() {
        InterviewSession session = new InterviewSession();
        session.setId(8001L);
        session.setUserId(USER_ID);
        session.setTitle("模拟面试");
        session.setStatus("FINISHED");
        return session;
    }

    private static InterviewReport report() {
        InterviewReport report = new InterviewReport();
        report.setId(1L);
        report.setSessionId(8001L);
        report.setUserId(USER_ID);
        report.setWeakPoints(List.of("JVM"));
        return report;
    }
}
