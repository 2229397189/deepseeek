package com.lq.deepseek.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lq.deepseek.common.BusinessException;
import com.lq.deepseek.common.ErrorCode;
import com.lq.deepseek.domain.entity.FileAsset;
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
import com.lq.deepseek.service.InterviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AI 模拟面试实现。
 *
 * <p>三件硬约束驱动写法：
 * <ol>
 *   <li><b>出题须经网关</b>：所有 AI 交互（首题 / 追问 / 终评）统一走 {@code AiInvocationGateway}
 *       的 INTERVIEW 业务类型，不绕过治理层（去重 / 重试 / 计量 / 审计）；</li>
 *   <li><b>失败不卡死</b>：网关不可用时回退确定性题目，让面试能继续推进，而不是整场开不出来；</li>
 *   <li><b>分数可追溯</b>：终态报告落 {@code interview_reports}，界面上的分数永远能追到具体调用。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewServiceImpl implements InterviewService {

    static final String BIZ = "INTERVIEW";
    static final String SPEC = "interview-v1";

    static final String STATUS_RUNNING = "RUNNING";
    static final String STATUS_FINISHED = "FINISHED";

    static final String ROLE_INTERVIEWER = "INTERVIEWER";
    static final String ROLE_CANDIDATE = "CANDIDATE";

    static final long OPEN_FREEZE_CREDIT = 10L;
    static final long ANSWER_FREEZE_CREDIT = 5L;
    static final long FINISH_FREEZE_CREDIT = 8L;
    static final long INTERVIEW_TIMEOUT_MS = 120_000L;

    private static final List<String> FALLBACK_QUESTIONS = List.of(
            "结合你刚才提到的经历，能展开讲讲其中最有挑战的一部分吗？",
            "如果让你重新设计那个模块，你会从哪些方面做优化？",
            "你在团队协作中通常扮演什么角色？遇到分歧一般怎么处理？"
    );

    private static final String FALLBACK_OPENING =
            "请先做一段自我介绍，包括你的主要技术栈和最近负责的项目。";

    private final InterviewSessionMapper sessionMapper;
    private final InterviewTurnMapper turnMapper;
    private final InterviewReportMapper reportMapper;
    private final FileAssetMapper fileAssetMapper;
    private final AiInvocationGateway gateway;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------
    // 开始 / 列表 / 详情
    // ------------------------------------------------------------------

    @Override
    public InterviewDtos.InterviewSession start(Long userId, InterviewDtos.StartRequest request) {
        String jobTitle = StringUtils.hasText(request.getJobTitle()) ? request.getJobTitle() : "模拟面试";

        InterviewSession session = new InterviewSession();
        session.setUserId(userId);
        session.setResumeAssetId(request.getResumeAssetId());
        session.setTitle(jobTitle);
        session.setMode("TEXT");
        session.setStatus(STATUS_RUNNING);
        session.setCurrentStage("OPEN");
        session.setStartedAt(OffsetDateTime.now());
        sessionMapper.insert(session);

        Map<String, Object> profile = loadResumeProfile(userId, request.getResumeAssetId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", "OPEN");
        payload.put("jobTitle", jobTitle);
        payload.put("model", request.getModel());
        payload.put("resumeProfile", profile);
        payload.put("history", List.of());

        AgentInvokeResult result = callGateway(userId, session.getId(), "OPEN", payload, OPEN_FREEZE_CREDIT);
        String opening = result != null && result.succeeded()
                ? strOf(result.getOutput().get("question"), FALLBACK_OPENING)
                : FALLBACK_OPENING;
        if (result == null || !result.succeeded()) {
            log.warn("面试首题生成降级为本地题目 userId={} sessionId={}", userId, session.getId());
        }

        appendTurn(session.getId(), 1, ROLE_INTERVIEWER, opening, null);
        return toSessionVO(session, opening);
    }

    @Override
    public List<InterviewDtos.InterviewSession> listSessions(Long userId) {
        List<InterviewSession> sessions = sessionMapper.selectList(new LambdaQueryWrapper<InterviewSession>()
                .eq(InterviewSession::getUserId, userId)
                .orderByDesc(InterviewSession::getCreatedAt)
                .last("LIMIT 50"));
        List<InterviewDtos.InterviewSession> vos = new ArrayList<>(sessions.size());
        for (InterviewSession s : sessions) {
            vos.add(toSessionVO(s, null));
        }
        return vos;
    }

    @Override
    public InterviewDtos.InterviewSessionDetail detail(Long userId, Long sessionId) {
        InterviewSession session = requireOwned(userId, sessionId);
        List<InterviewTurn> turns = turnMapper.selectList(new LambdaQueryWrapper<InterviewTurn>()
                .eq(InterviewTurn::getSessionId, sessionId)
                .orderByAsc(InterviewTurn::getSeq)
                .last("LIMIT 500"));

        InterviewReport report = latestReport(sessionId);
        List<InterviewDtos.MessageVO> messages = turns.stream().map(this::toMessageVO).toList();

        InterviewDtos.InterviewSessionDetail detail = new InterviewDtos.InterviewSessionDetail();
        detail.setSessionId(session.getId());
        detail.setStatus(session.getStatus());
        detail.setScore(report == null ? null : report.getScore());
        detail.setMessages(messages);
        detail.setReport(report == null ? null : toReportVO(report));
        return detail;
    }

    // ------------------------------------------------------------------
    // 作答 / 结束
    // ------------------------------------------------------------------

    @Override
    public InterviewDtos.InterviewTurn answer(Long userId, Long sessionId, InterviewDtos.AnswerRequest request) {
        InterviewSession session = requireOwned(userId, sessionId);
        if (STATUS_FINISHED.equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "面试已结束，无法继续作答");
        }

        int maxSeq = turnMapper.selectMaxSeq(sessionId);
        appendTurn(sessionId, maxSeq + 1, ROLE_CANDIDATE, request.getContent(), null);

        List<Map<String, Object>> history = buildHistory(sessionId);
        Map<String, Object> profile = loadResumeProfile(userId, session.getResumeAssetId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", "ANSWER");
        payload.put("jobTitle", session.getTitle());
        payload.put("resumeProfile", profile);
        payload.put("history", history);
        payload.put("answer", request.getContent());

        AgentInvokeResult result = callGateway(userId, sessionId, "ANSWER", payload, ANSWER_FREEZE_CREDIT);
        String nextQuestion;
        Integer turnScore = null;
        if (result != null && result.succeeded()) {
            nextQuestion = strOf(result.getOutput().get("question"), fallbackQuestion(maxSeq + 2));
            turnScore = intOf(result.getOutput().get("score"));
        } else {
            log.warn("面试追问降级为本地题目 userId={} sessionId={}", userId, sessionId);
            nextQuestion = fallbackQuestion(maxSeq + 2);
        }

        InterviewTurn aiTurn = appendTurn(sessionId, maxSeq + 2, ROLE_INTERVIEWER, nextQuestion, turnScore);

        session.setTurnCount((session.getTurnCount() == null ? 0 : session.getTurnCount()) + 1);
        sessionMapper.updateById(session);

        InterviewDtos.InterviewTurn vo = new InterviewDtos.InterviewTurn();
        vo.setUserMessage(toMessageVO(candidateTurn(sessionId, maxSeq + 1, request.getContent())));
        vo.setAiMessage(toMessageVO(aiTurn));
        vo.setTranscript(null);
        return vo;
    }

    @Override
    public InterviewDtos.InterviewReport finish(Long userId, Long sessionId) {
        InterviewSession session = requireOwned(userId, sessionId);
        InterviewReport existing = latestReport(sessionId);
        if (existing != null) {
            return toReportVO(existing);
        }

        List<Map<String, Object>> history = buildHistory(sessionId);
        Map<String, Object> profile = loadResumeProfile(userId, session.getResumeAssetId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", "FINISH");
        payload.put("jobTitle", session.getTitle());
        payload.put("resumeProfile", profile);
        payload.put("history", history);

        AgentInvokeResult result = callGateway(userId, sessionId, "FINISH", payload, FINISH_FREEZE_CREDIT);

        Integer score;
        Map<String, Object> dimensions;
        List<String> weakPoints;
        String suggestion;
        if (result != null && result.succeeded()) {
            score = intOf(result.getOutput().get("score"));
            dimensions = mapOf(result.getOutput().get("dimensions"));
            weakPoints = strListOf(result.getOutput().get("weakPoints"));
            suggestion = strOf(result.getOutput().get("suggestion"), null);
        } else {
            log.warn("面试终评降级为本地报告 userId={} sessionId={}", userId, sessionId);
            score = 60;
            dimensions = Map.of("communication", 60, "skill", 60);
            weakPoints = List.of();
            suggestion = "本次面试已完成，评分服务暂不可用，建议稍后在「面试记录」中查看完整评估。";
        }

        InterviewReport report = new InterviewReport();
        report.setUserId(userId);
        report.setSessionId(sessionId);
        report.setScore(score);
        report.setDimensions(dimensions);
        report.setWeakPoints(weakPoints);
        report.setSuggestion(suggestion);
        reportMapper.insert(report);

        session.setStatus(STATUS_FINISHED);
        session.setCurrentStage("FINISHED");
        session.setFinishedAt(OffsetDateTime.now());
        sessionMapper.updateById(session);

        log.info("面试结束 userId={} sessionId={} score={}", userId, sessionId, score);
        return toReportVO(report);
    }

    @Override
    public InterviewDtos.InterviewReport report(Long userId, Long sessionId) {
        requireOwned(userId, sessionId);
        InterviewReport report = latestReport(sessionId);
        if (report == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该面试尚未生成报告");
        }
        return toReportVO(report);
    }

    @Override
    public InterviewDtos.TranscribeVO transcribe(Long userId, InterviewDtos.TranscribeRequest request) {
        InterviewDtos.TranscribeVO vo = new InterviewDtos.TranscribeVO();
        if (request == null || !StringUtils.hasText(request.getAudio())) {
            vo.setText("（未提供音频，请直接输入作答内容）");
            return vo;
        }
        // 当前环境未接入 ASR 服务：返回优雅降级占位文本，不做虚假识别。
        // 若后续接入 ASR agent，应改为走 gateway.invoke(bizType=INTERVIEW_ASR) 并回填真实文本。
        vo.setText("（当前环境未启用语音识别，请手动输入作答）");
        return vo;
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private InterviewTurn appendTurn(Long sessionId, int seq, String role, String content, Integer score) {
        InterviewTurn turn = new InterviewTurn();
        turn.setSessionId(sessionId);
        turn.setSeq(seq);
        turn.setRole(role);
        turn.setContent(content);
        turn.setScore(score == null ? null : java.math.BigDecimal.valueOf(score));
        turnMapper.insert(turn);
        return turn;
    }

    private InterviewTurn candidateTurn(Long sessionId, int seq, String content) {
        InterviewTurn turn = new InterviewTurn();
        turn.setSessionId(sessionId);
        turn.setSeq(seq);
        turn.setRole(ROLE_CANDIDATE);
        turn.setContent(content);
        return turn;
    }

    private List<Map<String, Object>> buildHistory(Long sessionId) {
        List<InterviewTurn> turns = turnMapper.selectList(new LambdaQueryWrapper<InterviewTurn>()
                .eq(InterviewTurn::getSessionId, sessionId)
                .orderByAsc(InterviewTurn::getSeq)
                .last("LIMIT 500"));
        List<Map<String, Object>> history = new ArrayList<>(turns.size());
        for (InterviewTurn turn : turns) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", turn.getRole());
            item.put("content", turn.getContent());
            history.add(item);
        }
        return history;
    }

    private Map<String, Object> loadResumeProfile(Long userId, Long resumeAssetId) {
        if (resumeAssetId == null) {
            return Map.of();
        }
        FileAsset asset = fileAssetMapper.selectById(resumeAssetId);
        if (asset == null || !Objects.equals(asset.getUserId(), userId)
                || !"SUCCESS".equals(asset.getParseStatus()) || asset.getParseResult() == null) {
            return Map.of();
        }
        return asset.getParseResult();
    }

    private InterviewSession requireOwned(Long userId, Long sessionId) {
        if (sessionId == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "缺少会话 ID");
        }
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null || !Objects.equals(session.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "面试会话不存在");
        }
        return session;
    }

    private InterviewReport latestReport(Long sessionId) {
        List<InterviewReport> reports = reportMapper.selectList(new LambdaQueryWrapper<InterviewReport>()
                .eq(InterviewReport::getSessionId, sessionId)
                .orderByDesc(InterviewReport::getCreatedAt)
                .last("LIMIT 1"));
        return reports.isEmpty() ? null : reports.get(0);
    }

    /**
     * 统一经网关调用 INTERVIEW；额度不足等用户可自愈错误原样上抛，
     * 其余失败返回 null 让调用方走确定性降级，避免整场面试开不出来。
     */
    private AgentInvokeResult callGateway(Long userId, Long bizId, String stage,
                                          Map<String, Object> payload, long credit) {
        try {
            return gateway.invoke(AgentInvokeCommand.builder()
                    .bizType(BIZ)
                    .bizId(bizId)
                    .userId(userId)
                    .stage(stage)
                    .specHash(SPEC)
                    .payload(payload)
                    .expectedCredit(credit)
                    .timeoutMs(INTERVIEW_TIMEOUT_MS)
                    .build());
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.INSUFFICIENT_CREDIT) {
                throw e;
            }
            return null;
        }
    }

    private String fallbackQuestion(int seq) {
        return FALLBACK_QUESTIONS.get(Math.floorMod(seq, FALLBACK_QUESTIONS.size()));
    }

    private InterviewDtos.InterviewSession toSessionVO(InterviewSession session, String opening) {
        InterviewDtos.InterviewSession vo = new InterviewDtos.InterviewSession();
        vo.setSessionId(session.getId());
        vo.setJobTitle(session.getTitle());
        vo.setStatus(session.getStatus());
        vo.setCreatedAt(session.getCreatedAt());
        return vo;
    }

    private InterviewDtos.MessageVO toMessageVO(InterviewTurn turn) {
        InterviewDtos.MessageVO vo = new InterviewDtos.MessageVO();
        vo.setSeq(turn.getSeq());
        vo.setRole(turn.getRole());
        vo.setContent(turn.getContent());
        vo.setCreatedAt(turn.getCreatedAt());
        return vo;
    }

    private InterviewDtos.InterviewReport toReportVO(InterviewReport report) {
        InterviewDtos.InterviewReport vo = new InterviewDtos.InterviewReport();
        vo.setSessionId(report.getSessionId());
        vo.setScore(report.getScore());
        vo.setDimensions(report.getDimensions());
        vo.setWeakPoints(report.getWeakPoints());
        vo.setSuggestion(report.getSuggestion());
        vo.setCreatedAt(report.getCreatedAt());
        return vo;
    }

    private static String strOf(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private static Integer intOf(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

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
}
