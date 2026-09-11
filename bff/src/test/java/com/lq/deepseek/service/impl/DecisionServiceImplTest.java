package com.lq.deepseek.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lq.deepseek.common.BusinessException;
import com.lq.deepseek.common.ErrorCode;
import com.lq.deepseek.config.props.LqProperties;
import com.lq.deepseek.domain.entity.DecisionAnalysis;
import com.lq.deepseek.domain.entity.DecisionMessage;
import com.lq.deepseek.domain.entity.DecisionSession;
import com.lq.deepseek.domain.entity.FileAsset;
import com.lq.deepseek.domain.mapper.DecisionAnalysisMapper;
import com.lq.deepseek.domain.mapper.DecisionMessageMapper;
import com.lq.deepseek.domain.mapper.DecisionSessionMapper;
import com.lq.deepseek.domain.mapper.FileAssetMapper;
import com.lq.deepseek.dto.DecisionDtos;
import com.lq.deepseek.gateway.AiInvocationGateway;
import com.lq.deepseek.gateway.model.AgentInvokeCommand;
import com.lq.deepseek.gateway.model.AgentInvokeResult;
import com.lq.deepseek.service.support.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JD 分析模块行为测试。
 *
 * <p>钉死产品的四条硬要求：
 * <ol>
 *   <li>预览不落会话、不写历史（首页随手粘贴不能污染"我的分析"）；</li>
 *   <li>正式分析必须留下 分析明细 + 时间线消息 + 会话终态 三样，缺一不可；</li>
 *   <li>AI 失败要落 FAILED 并抛错，不能把失败渲染成 0 分；</li>
 *   <li>追问语料必须来自真实的 JD / 简历 / 结论文本。</li>
 * </ol>
 */
class DecisionServiceImplTest {

    private static final String JD_TEXT = """
            高级 Java 后端工程师
            岗位要求：熟悉 Java、Spring Boot、MySQL、Redis、Kafka，5 年以上经验，本科及以上学历。
            职责：负责核心交易链路的高并发改造与稳定性建设。
            """;

    @TempDir
    Path tempDir;

    private DecisionSessionMapper sessionMapper;
    private DecisionAnalysisMapper analysisMapper;
    private DecisionMessageMapper messageMapper;
    private FileAssetMapper fileAssetMapper;
    private AiInvocationGateway gateway;
    private DecisionServiceImpl service;

    private final List<DecisionAnalysis> storedAnalyses = new ArrayList<>();
    private final List<DecisionMessage> storedMessages = new ArrayList<>();
    private final List<DecisionSession> storedSessions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sessionMapper = mock(DecisionSessionMapper.class);
        analysisMapper = mock(DecisionAnalysisMapper.class);
        messageMapper = mock(DecisionMessageMapper.class);
        fileAssetMapper = mock(FileAssetMapper.class);
        gateway = mock(AiInvocationGateway.class);

        LqProperties properties = new LqProperties();
        properties.getStorage().setLocalRoot(tempDir.toString());
        service = new DecisionServiceImpl(sessionMapper, analysisMapper, messageMapper, fileAssetMapper,
                gateway, new FileStorageService(properties), new ObjectMapper());

        when(sessionMapper.insert(any(DecisionSession.class))).thenAnswer(invocation -> {
            DecisionSession session = invocation.getArgument(0);
            session.setId(9001L);
            storedSessions.add(session);
            return 1;
        });
        when(sessionMapper.selectById(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return storedSessions.stream().filter(s -> id.equals(s.getId())).findFirst().orElse(null);
        });
        when(analysisMapper.insert(any(DecisionAnalysis.class))).thenAnswer(invocation -> {
            DecisionAnalysis analysis = invocation.getArgument(0);
            analysis.setId(7001L);
            storedAnalyses.add(0, analysis);
            return 1;
        });
        when(analysisMapper.selectList(any())).thenAnswer(invocation -> List.copyOf(storedAnalyses));
        when(messageMapper.insert(any(DecisionMessage.class))).thenAnswer(invocation -> {
            storedMessages.add(invocation.getArgument(0));
            return 1;
        });
        when(messageMapper.selectList(any())).thenAnswer(invocation -> List.copyOf(storedMessages));
        when(messageMapper.selectMaxSeq(anyLong())).thenReturn(0, 1, 2, 3, 4, 5);
        // 生产环境由 MyBatis-Plus 回填自增主键，桩里必须补上，否则后续 updateParseState 会拿到 null
        when(fileAssetMapper.insert(any(FileAsset.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, FileAsset.class).setId(3001L);
            return 1;
        });
    }

    // -----------------------------------------------------------------------
    // 预览
    // -----------------------------------------------------------------------

    @Test
    void preview_returnsScoreAndGap_withoutTouchingHistory() {
        when(fileAssetMapper.selectById(5001L)).thenReturn(resumeAsset(5001L, 7L));
        when(gateway.invoke(any())).thenReturn(AgentInvokeResult.builder()
                .runId("run-preview").status("SUCCEEDED").flightMode("OWNER").costCredit(2L)
                .output(Map.of(
                        "score", 68,
                        "scoreBand", "可争取",
                        "conclusion", "HOLD",
                        "coveredSkills", List.of("Java", "Redis"),
                        "missingSkills", List.of("Kafka"),
                        "weakPointsHit", List.of("JVM"),
                        "risks", List.of("缺少 Kafka 实战"),
                        "advice", "预览模式仅给出分数与缺口。",
                        "steps", List.of()))
                .build());

        DecisionDtos.PreviewVO vo = service.preview(7L, previewRequest());

        assertThat(vo.getScore()).isEqualTo(68);
        assertThat(vo.getScoreBand()).isEqualTo("可争取");
        assertThat(vo.getMissingSkills()).containsExactly("Kafka");
        assertThat(vo.getPreview()).isTrue();

        ArgumentCaptor<AgentInvokeCommand> command = ArgumentCaptor.forClass(AgentInvokeCommand.class);
        verify(gateway).invoke(command.capture());
        assertThat(command.getValue().getBizType()).isEqualTo("DECIDE_PREVIEW");
        assertThat(command.getValue().getExpectedCredit()).isEqualTo(4L);
        assertThat(command.getValue().getPayload()).containsEntry("jdText", JD_TEXT.trim());

        // 预览绝不能产生会话与历史
        verify(sessionMapper, never()).insert(any(DecisionSession.class));
        verify(analysisMapper, never()).insert(any(DecisionAnalysis.class));
        verify(messageMapper, never()).insert(any(DecisionMessage.class));
    }

    @Test
    void preview_withoutJdText_isRejectedLocally() {
        when(fileAssetMapper.selectById(5001L)).thenReturn(resumeAsset(5001L, 7L));
        DecisionDtos.PreviewRequest request = new DecisionDtos.PreviewRequest();
        request.setResumeAssetId(5001L);

        assertThatThrownBy(() -> service.preview(7L, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);

        verify(gateway, never()).invoke(any());
    }

    // -----------------------------------------------------------------------
    // 正式分析
    // -----------------------------------------------------------------------

    @Test
    void analyze_createsSession_persistsAnalysisAndTimeline() {
        when(fileAssetMapper.selectById(5001L)).thenReturn(resumeAsset(5001L, 7L));
        when(gateway.invoke(any())).thenReturn(decideSuccess());

        DecisionDtos.AnalyzeRequest request = analyzeRequest();
        DecisionDtos.SessionDetailVO detail = service.analyze(7L, request);

        assertThat(detail.getSessionId()).isEqualTo(9001L);
        assertThat(detail.getLatestScore()).isNull(); // 会话终态由 markAnalyzed 落库，mock 下不回填
        assertThat(detail.getAnalyses()).hasSize(1);
        assertThat(detail.getLatestAnalysis().getScore()).isEqualTo(82);
        assertThat(detail.getLatestAnalysis().getScoreBand()).isEqualTo("高匹配");
        assertThat(detail.getLatestAnalysis().getMissingSkills()).containsExactly("Kafka");
        assertThat(detail.getLatestAnalysis().getRunId()).isEqualTo("run-decide");

        // 时间线：一条结论锚点 + 一条建议
        assertThat(detail.getMessages()).hasSize(2);
        assertThat(detail.getMessages().get(0).getRole()).isEqualTo("SYSTEM");
        assertThat(detail.getMessages().get(0).getContent()).contains("82");
        assertThat(detail.getMessages().get(1).getRole()).isEqualTo("ASSISTANT");

        verify(sessionMapper).markStatus(9001L, "RUNNING");
        verify(sessionMapper).markAnalyzed(eq(9001L), eq("FINISHED"), eq("run-decide"), eq(7001L),
                eq(82), anyString(), eq("高级 Java 后端工程师"));

        ArgumentCaptor<AgentInvokeCommand> command = ArgumentCaptor.forClass(AgentInvokeCommand.class);
        verify(gateway).invoke(command.capture());
        assertThat(command.getValue().getBizType()).isEqualTo("DECIDE");
        assertThat(command.getValue().getExpectedCredit()).isEqualTo(12L);
        assertThat(command.getValue().getPayload()).containsKeys("jdText", "profile", "resumeText");
    }

    @Test
    void analyze_agentFailure_marksSessionFailedAndSurfacesReason() {
        when(fileAssetMapper.selectById(5001L)).thenReturn(resumeAsset(5001L, 7L));
        when(gateway.invoke(any())).thenReturn(AgentInvokeResult.builder()
                .runId("run-decide").status("FAILED").errorCode("MODEL_TIMEOUT")
                .errorMsg("模型响应超时").build());

        assertThatThrownBy(() -> service.analyze(7L, analyzeRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("模型响应超时")
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_STAGE_FAILED);

        // 失败必须落状态，否则列表页会永远停在"分析中"
        verify(sessionMapper).markStatus(9001L, "RUNNING");
        verify(sessionMapper).markStatus(9001L, "FAILED");
        verify(analysisMapper, never()).insert(any(DecisionAnalysis.class));
    }

    @Test
    void analyze_continuesSession_reusesStoredJdText() {
        DecisionSession existing = new DecisionSession();
        existing.setId(9001L);
        existing.setUserId(7L);
        existing.setScene("JD_MATCH");
        existing.setTitle("高级 Java 后端工程师");
        existing.setJdText(JD_TEXT.trim());
        storedSessions.add(existing);

        when(fileAssetMapper.selectById(5001L)).thenReturn(resumeAsset(5001L, 7L));
        when(gateway.invoke(any())).thenReturn(decideSuccess());

        DecisionDtos.AnalyzeRequest request = new DecisionDtos.AnalyzeRequest();
        request.setSessionId(9001L);
        request.setResumeAssetId(5001L);
        request.setJobTitle("高级 Java 后端工程师");

        DecisionDtos.SessionDetailVO detail = service.analyze(7L, request);

        assertThat(detail.getSessionId()).isEqualTo(9001L);
        assertThat(detail.getJdCharCount()).isEqualTo(JD_TEXT.trim().length());
        verify(sessionMapper, never()).insert(any(DecisionSession.class));

        ArgumentCaptor<AgentInvokeCommand> command = ArgumentCaptor.forClass(AgentInvokeCommand.class);
        verify(gateway).invoke(command.capture());
        assertThat(command.getValue().getBizId()).isEqualTo(9001L);
        assertThat(command.getValue().getPayload()).containsEntry("jdText", JD_TEXT.trim());
    }

    @Test
    void analyze_withoutAnyJdSource_isRejectedLocally() {
        when(fileAssetMapper.selectById(5001L)).thenReturn(resumeAsset(5001L, 7L));
        DecisionDtos.AnalyzeRequest request = new DecisionDtos.AnalyzeRequest();
        request.setResumeAssetId(5001L);

        assertThatThrownBy(() -> service.analyze(7L, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);

        verify(gateway, never()).invoke(any());
        verify(sessionMapper, never()).insert(any(DecisionSession.class));
    }

    @Test
    void analyze_oversizedJd_isTruncatedToLimit() {
        when(fileAssetMapper.selectById(5001L)).thenReturn(resumeAsset(5001L, 7L));
        when(gateway.invoke(any())).thenReturn(decideSuccess());

        DecisionDtos.AnalyzeRequest request = new DecisionDtos.AnalyzeRequest();
        request.setResumeAssetId(5001L);
        request.setJdText("Java 岗位要求。".repeat(3000)); // 远超 20000 字上限

        service.analyze(7L, request);

        ArgumentCaptor<AgentInvokeCommand> command = ArgumentCaptor.forClass(AgentInvokeCommand.class);
        verify(gateway).invoke(command.capture());
        String sent = String.valueOf(command.getValue().getPayload().get("jdText"));
        assertThat(sent).hasSize(DecisionServiceImpl.JD_TEXT_LIMIT);
    }

    @Test
    void analyze_resumeOfAnotherUser_isReportedAsNotFound() {
        when(fileAssetMapper.selectById(5001L)).thenReturn(resumeAsset(5001L, 99L));

        assertThatThrownBy(() -> service.analyze(7L, analyzeRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILE_NOT_FOUND);

        verify(gateway, never()).invoke(any());
    }

    @Test
    void analyze_unparsedResume_isRejectedBeforeCallingAgent() {
        FileAsset asset = resumeAsset(5001L, 7L);
        asset.setParseStatus("FAILED");
        asset.setParseResult(null);
        when(fileAssetMapper.selectById(5001L)).thenReturn(asset);

        assertThatThrownBy(() -> service.analyze(7L, analyzeRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(gateway, never()).invoke(any());
    }

    // -----------------------------------------------------------------------
    // JD 上传
    // -----------------------------------------------------------------------

    @Test
    void uploadJd_extractsTextAndPersistsAsset() {
        when(fileAssetMapper.selectActiveBySha(anyLong(), anyString(), eq("JD"))).thenReturn(null);
        when(gateway.invoke(any())).thenReturn(AgentInvokeResult.builder()
                .runId("run-jd").status("SUCCEEDED").flightMode("OWNER").costCredit(1L)
                .output(Map.of(
                        "sourceKind", "FILE",
                        "text", JD_TEXT.trim(),
                        "charCount", JD_TEXT.trim().length(),
                        "hardRequirements", List.of("5 年以上经验", "本科及以上学历"),
                        "skills", List.of("Java", "Spring Boot", "Redis")))
                .build());

        DecisionDtos.JdUploadVO vo = service.uploadJd(7L, file("jd.txt", JD_TEXT));

        assertThat(vo.getParseStatus()).isEqualTo("SUCCESS");
        assertThat(vo.getDeduplicated()).isFalse();
        assertThat(vo.getCharCount()).isEqualTo(JD_TEXT.trim().length());
        assertThat(vo.getSkills()).contains("Redis");
        assertThat(vo.getPreview()).contains("高级 Java 后端工程师");

        ArgumentCaptor<AgentInvokeCommand> command = ArgumentCaptor.forClass(AgentInvokeCommand.class);
        verify(gateway).invoke(command.capture());
        assertThat(command.getValue().getBizType()).isEqualTo("RESUME_PARSE");
        assertThat(command.getValue().getPayload()).containsEntry("mode", "JD_PARSE");
        verify(fileAssetMapper).updateParseState(anyLong(), eq("SUCCESS"), anyString(), eq(null));
    }

    @Test
    void uploadJd_sameContent_reusesAssetWithoutSecondParse() {
        FileAsset existing = new FileAsset();
        existing.setId(3001L);
        existing.setUserId(7L);
        existing.setBizType("JD");
        existing.setFileName("jd.txt");
        existing.setParseStatus("SUCCESS");
        existing.setParseResult(Map.of("text", JD_TEXT.trim(), "charCount", 120, "skills", List.of("Java")));
        when(fileAssetMapper.selectActiveBySha(anyLong(), anyString(), eq("JD"))).thenReturn(existing);

        DecisionDtos.JdUploadVO vo = service.uploadJd(7L, file("jd.txt", JD_TEXT));

        assertThat(vo.getAssetId()).isEqualTo(3001L);
        assertThat(vo.getDeduplicated()).isTrue();
        verify(gateway, never()).invoke(any());
        verify(fileAssetMapper, never()).insert(any(FileAsset.class));
    }

    @Test
    void uploadJd_unsupportedExtension_isRejectedBeforeStorage() {
        assertThatThrownBy(() -> service.uploadJd(7L, file("jd.doc", "binary")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILE_TYPE_UNSUPPORTED);

        verify(gateway, never()).invoke(any());
        verify(fileAssetMapper, never()).insert(any(FileAsset.class));
    }

    // -----------------------------------------------------------------------
    // 追问
    // -----------------------------------------------------------------------

    @Test
    void ask_usesRealJdResumeAndConclusionAsCorpus() {
        DecisionSession existing = new DecisionSession();
        existing.setId(9001L);
        existing.setUserId(7L);
        existing.setScene("JD_MATCH");
        existing.setTitle("高级 Java 后端工程师");
        existing.setJdText(JD_TEXT.trim());
        existing.setResumeAssetId(5001L);
        existing.setLatestAnalysisId(7001L);
        storedSessions.add(existing);

        DecisionAnalysis latest = new DecisionAnalysis();
        latest.setId(7001L);
        latest.setSessionId(9001L);
        latest.setScore(82);
        latest.setConclusion("APPLY");
        latest.setMissingSkills(List.of("Kafka"));
        latest.setRisks(List.of("缺少 Kafka 实战"));
        latest.setTodos(List.of("补齐 Kafka"));
        latest.setAdvice("建议补齐消息队列后投递。");
        when(analysisMapper.selectById(7001L)).thenReturn(latest);
        when(fileAssetMapper.selectById(5001L)).thenReturn(resumeAsset(5001L, 7L));
        when(gateway.invoke(any())).thenReturn(AgentInvokeResult.builder()
                .runId("run-ask").status("SUCCEEDED").flightMode("OWNER").costCredit(3L)
                .output(Map.of("answer", "JD 未提及 Kafka，属于加分项。", "hits", List.of()))
                .build());

        DecisionDtos.AskRequest request = new DecisionDtos.AskRequest();
        request.setQuestion("这个岗位要求 Kafka 吗？");
        DecisionDtos.AskVO vo = service.ask(7L, 9001L, request);

        assertThat(vo.getAnswer()).contains("Kafka");

        ArgumentCaptor<AgentInvokeCommand> command = ArgumentCaptor.forClass(AgentInvokeCommand.class);
        verify(gateway).invoke(command.capture());
        assertThat(command.getValue().getBizType()).isEqualTo("RAG_SEARCH");
        assertThat(command.getValue().getSpecHash()).isEqualTo("decision-ask-v1");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents = (List<Map<String, Object>>) command.getValue()
                .getPayload().get("documents");
        assertThat(documents).isNotEmpty();
        assertThat(documents).allSatisfy(doc -> assertThat(doc.get("content")).asString().isNotBlank());
        assertThat(documents.stream().map(doc -> String.valueOf(doc.get("documentId"))))
                .anyMatch(id -> id.startsWith("JD-9001"))
                .anyMatch(id -> id.startsWith("RESUME-5001"))
                .anyMatch(id -> id.startsWith("ANALYSIS-7001"));

        // 追问要进时间线，刷新后能接着聊
        verify(messageMapper, org.mockito.Mockito.times(2)).insert(any(DecisionMessage.class));
    }

    @Test
    void archive_otherUserSession_isReportedAsNotFound() {
        DecisionSession other = new DecisionSession();
        other.setId(9001L);
        other.setUserId(99L);
        storedSessions.add(other);

        assertThatThrownBy(() -> service.archive(7L, 9001L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);

        verify(sessionMapper, never()).deleteById(anyLong());
    }

    // -----------------------------------------------------------------------

    private DecisionDtos.PreviewRequest previewRequest() {
        DecisionDtos.PreviewRequest request = new DecisionDtos.PreviewRequest();
        request.setResumeAssetId(5001L);
        request.setJdText("  " + JD_TEXT.trim() + "  ");
        return request;
    }

    private DecisionDtos.AnalyzeRequest analyzeRequest() {
        DecisionDtos.AnalyzeRequest request = new DecisionDtos.AnalyzeRequest();
        request.setResumeAssetId(5001L);
        request.setJdText(JD_TEXT);
        request.setJobTitle("高级 Java 后端工程师");
        return request;
    }

    private static AgentInvokeResult decideSuccess() {
        // Map.of 最多 10 对键值，字段一多就必须用 LinkedHashMap（顺序也更贴近真实响应）
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("score", 82);
        output.put("conclusion", "APPLY");
        output.put("dimensions", Map.of("skillCoverage", 80.0));
        output.put("requiredSkills", List.of("Java", "Spring Boot", "Redis", "Kafka"));
        output.put("coveredSkills", List.of("Java", "Spring Boot", "Redis"));
        output.put("missingSkills", List.of("Kafka"));
        output.put("weakPointsHit", List.of());
        output.put("hardRequirements", List.of("5 年以上经验"));
        output.put("risks", List.of("缺少 Kafka 实战"));
        output.put("advice", "建议先补齐 Kafka 再投递。");
        output.put("todos", List.of("补齐 Kafka"));
        output.put("steps", List.of(Map.of("name", "parse_jd", "status", "DONE")));
        return AgentInvokeResult.builder()
                .runId("run-decide").status("SUCCEEDED").flightMode("OWNER").costCredit(11L)
                .output(output)
                .build();
    }

    private static FileAsset resumeAsset(Long id, Long userId) {
        FileAsset asset = new FileAsset();
        asset.setId(id);
        asset.setUserId(userId);
        asset.setBizType("RESUME");
        asset.setFileName("resume.txt");
        asset.setParseStatus("SUCCESS");
        asset.setParseResult(Map.of(
                "sourceKind", "FILE",
                "completeness", 88,
                "skills", List.of("Java", "Spring Boot", "Redis"),
                "experiences", List.of("订单中心重构"),
                "projects", List.of("AI 模拟面试平台"),
                "weakPoints", List.of()));
        return asset;
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }
}
