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
import com.lq.deepseek.dto.InterviewDtos;
import com.lq.deepseek.gateway.AiInvocationGateway;
import com.lq.deepseek.gateway.model.AgentInvokeCommand;
import com.lq.deepseek.gateway.model.AgentInvokeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
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
 * AI 模拟面试模块行为测试。
 *
 * <p>钉死四条约束：
 * <ol>
 *   <li>所有 AI 交互都经网关走 INTERVIEW 业务类型，不绕过治理层；</li>
 *   <li>网关不可用时降级为确定性题目，面试不能开不出来；</li>
 *   <li>作答要落"用户 + AI"两条消息，刷新后能接着聊；</li>
 *   <li>结束面试必须落报告并把会话置 FINISHED。</li>
 * </ol>
 */
class InterviewServiceImplTest {

    private static final Long USER_ID = 7L;
    private static final Long SESSION_ID = 9001L;

    private InterviewSessionMapper sessionMapper;
    private InterviewTurnMapper turnMapper;
    private InterviewReportMapper reportMapper;
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
        fileAssetMapper = mock(FileAssetMapper.class);
        gateway = mock(AiInvocationGateway.class);
        service = new InterviewServiceImpl(sessionMapper, turnMapper, reportMapper, fileAssetMapper,
                gateway, new ObjectMapper());

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
        when(turnMapper.selectList(any())).thenAnswer(invocation -> List.copyOf(storedTurns));
        when(turnMapper.selectMaxSeq(anyLong())).thenReturn(1, 2, 3, 4, 5, 6);
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
                .output(Map.of("question", "请先介绍一下你自己。"))
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
        assertThat(command.getValue().getStage()).isEqualTo("OPEN");
        assertThat(command.getValue().getUserId()).isEqualTo(USER_ID);

        // 首题必须落库，前端详情才能读到
        assertThat(storedTurns).hasSize(1);
        assertThat(storedTurns.get(0).getRole()).isEqualTo("INTERVIEWER");
        assertThat(storedTurns.get(0).getContent()).isEqualTo("请先介绍一下你自己。");
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
                .output(Map.of("question", "讲讲你最有挑战的项目。", "score", 80))
                .build());

        InterviewDtos.AnswerRequest request = new InterviewDtos.AnswerRequest();
        request.setContent("我做过订单中心重构。");
        InterviewDtos.InterviewTurn turn = service.answer(USER_ID, SESSION_ID, request);

        assertThat(turn.getUserMessage().getRole()).isEqualTo("CANDIDATE");
        assertThat(turn.getUserMessage().getContent()).isEqualTo("我做过订单中心重构。");
        assertThat(turn.getAiMessage().getRole()).isEqualTo("INTERVIEWER");
        assertThat(turn.getAiMessage().getContent()).isEqualTo("讲讲你最有挑战的项目。");

        // 一问一答：两条消息都落库
        assertThat(storedTurns).hasSize(2);
        assertThat(storedTurns.get(0).getRole()).isEqualTo("CANDIDATE");
        assertThat(storedTurns.get(1).getRole()).isEqualTo("INTERVIEWER");

        ArgumentCaptor<AgentInvokeCommand> command = ArgumentCaptor.forClass(AgentInvokeCommand.class);
        verify(gateway).invoke(command.capture());
        assertThat(command.getValue().getBizType()).isEqualTo("INTERVIEW");
        assertThat(command.getValue().getStage()).isEqualTo("ANSWER");
        assertThat(command.getValue().getPayload()).containsEntry("answer", "我做过订单中心重构。");
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
    // 结束 / 报告
    // ------------------------------------------------------------------

    @Test
    void finish_persistsReport_andMarksSessionFinished() {
        storedSessions.add(newSession(SESSION_ID));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("score", 82);
        output.put("dimensions", Map.of("skill", 85));
        output.put("weakPoints", List.of("Kafka"));
        output.put("suggestion", "建议补齐消息队列。");
        when(gateway.invoke(any())).thenReturn(AgentInvokeResult.builder()
                .runId("run-finish").status("SUCCEEDED").flightMode("OWNER").output(output).build());

        InterviewDtos.InterviewReport report = service.finish(USER_ID, SESSION_ID);

        assertThat(report.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(report.getScore()).isEqualTo(82);
        assertThat(report.getWeakPoints()).containsExactly("Kafka");
        assertThat(report.getSuggestion()).isEqualTo("建议补齐消息队列。");
        assertThat(storedReports).hasSize(1);

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
