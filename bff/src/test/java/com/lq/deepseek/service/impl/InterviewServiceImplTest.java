package com.lq.deepseek.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lq.deepseek.common.BusinessException;
import com.lq.deepseek.common.ErrorCode;
import com.lq.deepseek.domain.entity.InterviewReport;
import com.lq.deepseek.domain.entity.InterviewSession;
import com.lq.deepseek.domain.entity.InterviewTurn;
import com.lq.deepseek.domain.mapper.FileAssetMapper;
import com.lq.deepseek.domain.mapper.InterviewReportMapper;
import com.lq.deepseek.domain.mapper.InterviewSessionMapper;
import com.lq.deepseek.domain.mapper.InterviewTurnMapper;
import com.lq.deepseek.domain.mapper.LongTermMemoryMapper;
import com.lq.deepseek.domain.mapper.InterviewReportMapper;
import com.lq.deepseek.domain.mapper.InterviewSessionMapper;
import com.lq.deepseek.domain.mapper.InterviewTurnMapper;
import com.lq.deepseek.dto.InterviewDtos;
import com.lq.deepseek.gateway.AiInvocationGateway;
import com.lq.deepseek.gateway.model.AgentInvokeCommand;
import com.lq.deepseek.gateway.model.AgentInvokeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 模拟面试模块行为测试（P0-6：mode 契约 + questionPlan 推进）。
 *
 * <p>钉死五条约束：
 * <ol>
 *   <li>所有 AI 交互都经网关走 INTERVIEW 业务类型，不绕过治理层；</li>
 *   <li>网关不可用时降级为确定性题目，面试不能开不出来；</li>
 *   <li>作答要落"用户 + AI"两条消息，刷新后能接着聊；</li>
 *   <li>结束面试必须落报告并把会话置 FINISHED；</li>
 *   <li>出题引擎以 agent 的 questionPlan 为权威，BFF 用 mode 字段（START/ANSWER/NEXT/FINISH）驱动。</li>
 * </ol>
 */
class InterviewServiceImplTest {

    private static final Long USER_ID = 7L;
    private static final Long SESSION_ID = 9001L;

    private InterviewSessionMapper sessionMapper;
    private InterviewTurnMapper turnMapper;
    private InterviewReportMapper reportMapper;
    private LongTermMemoryMapper longTermMemoryMapper;
    private FileAssetMapper fileAssetMapper;
    private AiInvocationGateway gateway;
    private InterviewServiceImpl service;

    private final List<InterviewTurn> storedTurns = new ArrayList<>();
    private final List<InterviewReport> storedReports = new ArrayList<>();
    private final List<InterviewSession> storedSessions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sessionMapper = mock(InterviewSessionMapper.class);
        turnMapper = mock(InterviewTurnMapper.class);
        reportMapper = mock(InterviewReportMapper.class);
        longTermMemoryMapper = mock(LongTermMemoryMapper.class);
        fileAssetMapper = mock(FileAssetMapper.class);
        gateway = mock(AiInvocationGateway.class);
        service = new InterviewServiceImpl(sessionMapper, turnMapper, reportMapper, longTermMemoryMapper,
                fileAssetMapper, gateway, new ObjectMapper());

        when(sessionMapper.insert(any(InterviewSession.class))).thenAnswer(invocation -> {
            InterviewSession s = invocation.getArgument(0);
            s.setId(SESSION_ID);
            storedSessions.add(s);
            return 1;
        });
        when(sessionMapper.selectById(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return storedSessions.stream().filter(s -> id.equals(s.getId())).findFirst().orElse(null);
        });
        when(turnMapper.insert(any(InterviewTurn.class))).thenAnswer(invocation -> {
            storedTurns.add(invocation.getArgument(0));
            return 1;
        });
        when(turnMapper.updateById(any(InterviewTurn.class))).thenReturn(1);
        when(turnMapper.selectList(any())).thenAnswer(invocation -> List.copyOf(storedTurns));
        when(turnMapper.selectOne(any())).thenAnswer(invocation -> storedTurns.stream()
                .filter(t -> "INTERVIEWER".equals(t.getRole()))
                .max(Comparator.comparingInt(InterviewTurn::getSeq))
                .orElse(null));
        when(turnMapper.selectMaxSeq(anyLong())).thenAnswer(invocation ->
                storedTurns.stream().mapToInt(InterviewTurn::getSeq).max().orElse(0));
        when(reportMapper.insert(any(InterviewReport.class))).thenAnswer(invocation -> {
            InterviewReport r = invocation.getArgument(0);
            r.setId(7001L);
            storedReports.add(r);
            return 1;
        });
        when(reportMapper.selectList(any())).thenAnswer(invocation -> List.copyOf(storedReports));
        when(sessionMapper.updateById(any(InterviewSession.class))).thenReturn(1);
    }

    // ------------------------------------------------------------------
    // 开始
    // ------------------------------------------------------------------

    @Test
    void start_createsSession_andGeneratesOpeningQuestionThroughGateway() {
        when(gateway.invoke(any())).thenReturn(AgentInvokeResult.builder()
                .runId("run-open").status("SUCCEEDED").flightMode("OWNER")
                .output(Map.of("question", "请先介绍一下你自己。",
                        "questionPlan", List.of(Map.of("skill", "Redis", "reason", "简历技能")),
                        "skill", "Redis", "questionIndex", 1, "weakPointsTracking", List.of()))
                .build());

        InterviewDtos.StartRequest request = new InterviewDtos.StartRequest();
        request.setJobTitle("高级 Java 后端工程师");
        InterviewDtos.InterviewSession vo = service.start(USER_ID, request);

        assertThat(vo.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(vo.getJobTitle()).isEqualTo("高级 Java 后端工程师");
        assertThat(vo.getStatus()).isEqualTo("RUNNING");

        ArgumentCaptor<AgentInvokeCommand> command = ArgumentCaptor.forClass(AgentInvokeCommand.class);
        verify(gateway).invoke(command.capture());
        assertThat(command.getValue().getBizType()).isEqualTo("INTERVIEW");
        assertThat(command.getValue().getStage()).isEqualTo("START");
        assertThat(command.getValue().getPayload()).containsEntry("mode", "START");
        assertThat(command.getValue().getUserId()).isEqualTo(USER_ID);

        // 首题必须落库，前端详情才能读到；questionPlan 必须写入 stateSnapshot
        assertThat(storedTurns).hasSize(1);
        assertThat(storedTurns.get(0).getRole()).isEqualTo("INTERVIEWER");
        assertThat(storedTurns.get(0).getContent()).isEqualTo("请先介绍一下你自己。");
        assertThat(storedTurns.get(0).getSkill()).isEqualTo("Redis");

        ArgumentCaptor<InterviewSession> snap = ArgumentCaptor.forClass(InterviewSession.class);
        verify(sessionMapper).updateById(snap.capture());
        assertThat(snap.getValue().getStateSnapshot()).containsKey("questionPlan");
        assertThat(snap.getValue().getStateSnapshot()).containsEntry("currentSkill", "Redis");
    }

    @Test
    void start_gatewayFailure_fallsBackToLocalOpeningQuestion() {
        when(gateway.invoke(any()))
                .thenThrow(new BusinessException(ErrorCode.AGENT_UNAVAILABLE));

        InterviewDtos.StartRequest request = new InterviewDtos.StartRequest();
        request.setJobTitle("模拟面试");
        InterviewDtos.InterviewSession vo = service.start(USER_ID, request);

        // 面试不能因为 AI 不可用就开不出来
        assertThat(vo.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(storedTurns).hasSize(1);
        assertThat(storedTurns.get(0).getContent()).isNotBlank();
    }

    // ------------------------------------------------------------------
    // 作答
    // ------------------------------------------------------------------

    @Test
    void answer_persistsUserAndAiMessages_andReturnsNextQuestion() {
        storedSessions.add(newSession(SESSION_ID));
        when(gateway.invoke(any())).thenReturn(AgentInvokeResult.builder()
                .runId("run-answer").status("SUCCEEDED").flightMode("OWNER")
                .output(Map.of("question", "讲讲你最有挑战的项目。", "score", 80,
                        "nextSkill", "MySQL", "isFinished", false, "hasFollowUp", false,
                        "matchedKeywords", List.of("方案"), "missingKeywords", List.of("数据"),
                        "weakPointsUpdate", List.of()))
                .build());

        InterviewDtos.AnswerRequest request = new InterviewDtos.AnswerRequest();
        request.setContent("我做过订单中心重构。");
        InterviewDtos.InterviewTurn turn = service.answer(USER_ID, SESSION_ID, request);

        assertThat(turn.getUserMessage().getRole()).isEqualTo("CANDIDATE");
        assertThat(turn.getUserMessage().getContent()).isEqualTo("我做过订单中心重构。");
        assertThat(turn.getUserMessage().getQuestionScore()).isEqualTo(80);
        assertThat(turn.getAiMessage().getRole()).isEqualTo("INTERVIEWER");
        assertThat(turn.getAiMessage().getContent()).isEqualTo("讲讲你最有挑战的项目。");

        // 当前题目(已评分) + 用户作答 + 下一题：共三条消息落库
        assertThat(storedTurns).hasSize(3);
        assertThat(storedTurns.get(0).getRole()).isEqualTo("INTERVIEWER");
        assertThat(storedTurns.get(1).getRole()).isEqualTo("CANDIDATE");
        assertThat(storedTurns.get(2).getRole()).isEqualTo("INTERVIEWER");

        // 当前题目必须把分数回填，供 buildHistory 与终评聚合
        ArgumentCaptor<InterviewTurn> turnCap = ArgumentCaptor.forClass(InterviewTurn.class);
        verify(turnMapper).updateById(turnCap.capture());
        assertThat(turnCap.getValue().getScore()).isEqualTo(BigDecimal.valueOf(80));
        assertThat(turnCap.getValue().getRole()).isEqualTo("INTERVIEWER");

        ArgumentCaptor<AgentInvokeCommand> command = ArgumentCaptor.forClass(AgentInvokeCommand.class);
        verify(gateway).invoke(command.capture());
        assertThat(command.getValue().getBizType()).isEqualTo("INTERVIEW");
        assertThat(command.getValue().getStage()).isEqualTo("ANSWER");
        assertThat(command.getValue().getPayload()).containsEntry("mode", "ANSWER");
        assertThat(command.getValue().getPayload()).containsEntry("answer", "我做过订单中心重构。");
    }

    @Test
    void answer_propagatesQuestionPlanAndSkillIntoHistory() {
        // 预置已评分的首题 + 用户作答，模拟进行中的面试
        InterviewTurn q1 = new InterviewTurn();
        q1.setId(101L); q1.setSessionId(SESSION_ID); q1.setSeq(1); q1.setRole("INTERVIEWER");
        q1.setContent("讲讲 Redis"); q1.setScore(BigDecimal.valueOf(80)); q1.setSkill("Redis");
        storedTurns.add(q1);
        InterviewTurn a1 = new InterviewTurn();
        a1.setId(102L); a1.setSessionId(SESSION_ID); a1.setSeq(2); a1.setRole("CANDIDATE");
        a1.setContent("我用 Redis 做了缓存。");
        storedTurns.add(a1);

        InterviewSession session = newSession(SESSION_ID);
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("questionPlan", List.of(Map.of("skill", "Redis", "reason", "简历技能"),
                Map.of("skill", "JVM 调优", "reason", "做深做透")));
        snap.put("currentSkill", "Redis");
        snap.put("questionIndex", 1);
        snap.put("weakPoints", List.of("分布式事务"));
        snap.put("followUpCount", 0);
        session.setStateSnapshot(snap);
        storedSessions.add(session);

        when(gateway.invoke(any())).thenReturn(AgentInvokeResult.builder()
                .runId("run-answer").status("SUCCEEDED").flightMode("OWNER")
                .output(Map.of("question", "讲讲 JVM 调优", "score", 75, "nextSkill", "JVM 调优",
                        "isFinished", false, "hasFollowUp", false, "weakPointsUpdate", List.of("分布式事务"),
                        "matchedKeywords", List.of("gc"), "missingKeywords", List.of("调优")))
                .build());

        InterviewDtos.AnswerRequest request = new InterviewDtos.AnswerRequest();
        request.setContent("缓存穿透用布隆过滤器。");
        service.answer(USER_ID, SESSION_ID, request);

        ArgumentCaptor<AgentInvokeCommand> command = ArgumentCaptor.forClass(AgentInvokeCommand.class);
        verify(gateway).invoke(command.capture());
        Map<String, Object> payload = command.getValue().getPayload();
        assertThat(payload).containsEntry("mode", "ANSWER");
        assertThat(payload).containsEntry("skill", "Redis");
        assertThat(payload).containsKey("questionPlan");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) payload.get("history");
        assertThat(history).hasSize(1);
        assertThat(history.get(0)).containsEntry("skill", "Redis");
        assertThat(history.get(0)).containsEntry("score", 80);
        assertThat(history.get(0).get("question")).isNotNull();
    }

    @Test
    void answer_finishedSession_isRejected() {
        InterviewSession finished = newSession(SESSION_ID);
        finished.setStatus("FINISHED");
        storedSessions.add(finished);

        InterviewDtos.AnswerRequest request = new InterviewDtos.AnswerRequest();
        request.setContent("还能回答吗？");

        assertThatThrownBy(() -> service.answer(USER_ID, SESSION_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(gateway, org.mockito.Mockito.never()).invoke(any());
    }

    @Test
    void answer_otherUserSession_isReportedAsNotFound() {
        InterviewSession other = newSession(SESSION_ID);
        other.setUserId(99L);
        storedSessions.add(other);

        InterviewDtos.AnswerRequest request = new InterviewDtos.AnswerRequest();
        request.setContent("越权尝试");

        assertThatThrownBy(() -> service.answer(USER_ID, SESSION_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ------------------------------------------------------------------
    // NEXT 阶段
    // ------------------------------------------------------------------

    @Test
    void next_advancesViaNextMode_andStoresPlan() {
        InterviewSession session = newSession(SESSION_ID);
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("questionPlan", List.of(Map.of("skill", "Redis", "reason", "简历技能"),
                Map.of("skill", "JVM 调优", "reason", "做深做透")));
        snap.put("currentSkill", "Redis");
        snap.put("questionIndex", 1);
        snap.put("weakPoints", List.of());
        snap.put("followUpCount", 0);
        session.setStateSnapshot(snap);
        storedSessions.add(session);

        when(gateway.invoke(any())).thenReturn(AgentInvokeResult.builder()
                .runId("run-next").status("SUCCEEDED").flightMode("OWNER")
                .output(Map.of("question", "讲讲 JVM 调优", "skill", "JVM 调优",
                        "isFinished", false, "questionIndex", 2))
                .build());

        InterviewDtos.MessageVO vo = service.nextQuestion(USER_ID, SESSION_ID);

        assertThat(vo.getRole()).isEqualTo("INTERVIEWER");
        assertThat(vo.getContent()).isEqualTo("讲讲 JVM 调优");
        assertThat(vo.getQuestionIndex()).isEqualTo(2);

        ArgumentCaptor<AgentInvokeCommand> command = ArgumentCaptor.forClass(AgentInvokeCommand.class);
        verify(gateway).invoke(command.capture());
        assertThat(command.getValue().getStage()).isEqualTo("NEXT");
        assertThat(command.getValue().getPayload()).containsEntry("mode", "NEXT");
        assertThat(command.getValue().getPayload()).containsKey("questionPlan");
    }

    // ------------------------------------------------------------------
    // 结束 / 报告
    // ------------------------------------------------------------------

    @Test
    void finish_persistsReport_andMarksSessionFinished() {
        storedSessions.add(newSession(SESSION_ID));
        // 预置一条已评分的首题，让 buildHistory 能透传 skill
        InterviewTurn q1 = new InterviewTurn();
        q1.setId(201L); q1.setSessionId(SESSION_ID); q1.setSeq(1); q1.setRole("INTERVIEWER");
        q1.setContent("讲讲 Redis"); q1.setScore(BigDecimal.valueOf(82)); q1.setSkill("Redis");
        storedTurns.add(q1);
        InterviewTurn a1 = new InterviewTurn();
        a1.setId(202L); a1.setSessionId(SESSION_ID); a1.setSeq(2); a1.setRole("CANDIDATE");
        a1.setContent("我用 Redis 做了缓存。");
        storedTurns.add(a1);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("totalScore", 82);
        output.put("dimensions", Map.of("技术深度", 85, "表达结构", 80, "数据意识", 70));
        output.put("weakPointsUpdate", List.of("Kafka"));
        output.put("report", "整体表现扎实，建议补齐消息队列。");
        when(gateway.invoke(any())).thenReturn(AgentInvokeResult.builder()
                .runId("run-finish").status("SUCCEEDED").flightMode("OWNER").output(output).build());

        InterviewDtos.InterviewReport report = service.finish(USER_ID, SESSION_ID);

        assertThat(report.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(report.getScore()).isEqualTo(82);
        assertThat(report.getWeakPoints()).containsExactly("Kafka");
        assertThat(report.getSuggestion()).isEqualTo("整体表现扎实，建议补齐消息队列。");
        assertThat(storedReports).hasSize(1);

        ArgumentCaptor<AgentInvokeCommand> command = ArgumentCaptor.forClass(AgentInvokeCommand.class);
        verify(gateway).invoke(command.capture());
        assertThat(command.getValue().getStage()).isEqualTo("FINISH");
        assertThat(command.getValue().getPayload()).containsEntry("mode", "FINISH");

        ArgumentCaptor<InterviewSession> session = ArgumentCaptor.forClass(InterviewSession.class);
        verify(sessionMapper).updateById(session.capture());
        assertThat(session.getValue().getStatus()).isEqualTo("FINISHED");
    }

    @Test
    void finish_gatewayFailure_returnsDegradedReport() {
        storedSessions.add(newSession(SESSION_ID));
        when(gateway.invoke(any()))
                .thenThrow(new BusinessException(ErrorCode.AGENT_UNAVAILABLE));

        InterviewDtos.InterviewReport report = service.finish(USER_ID, SESSION_ID);

        // 降级报告仍要落库，保证"结束面试"这个动作有终态
        assertThat(report.getScore()).isEqualTo(60);
        assertThat(report.getWeakPoints()).isEmpty();
        assertThat(storedReports).hasSize(1);
    }

    @Test
    void report_missingReport_isRejected() {
        storedSessions.add(newSession(SESSION_ID));
        when(reportMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.report(USER_ID, SESSION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ------------------------------------------------------------------
    // 转写
    // ------------------------------------------------------------------

    @Test
    void transcribe_withoutAsr_returnsGracefulPlaceholder() {
        InterviewDtos.TranscribeRequest request = new InterviewDtos.TranscribeRequest();
        request.setAudio("base64-audio");
        request.setFormat("wav");

        InterviewDtos.TranscribeVO vo = service.transcribe(USER_ID, request);
        assertThat(vo.getText()).isNotBlank();
        verify(gateway, org.mockito.Mockito.never()).invoke(any());
    }

    // ------------------------------------------------------------------

    private static InterviewSession newSession(Long id) {
        InterviewSession session = new InterviewSession();
        session.setId(id);
        session.setUserId(USER_ID);
        session.setTitle("高级 Java 后端工程师");
        session.setMode("TEXT");
        session.setStatus("RUNNING");
        return session;
    }
}
