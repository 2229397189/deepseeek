package com.lq.deepseek.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lq.deepseek.common.BusinessException;
import com.lq.deepseek.common.ErrorCode;
import com.lq.deepseek.domain.entity.FileAsset;
import com.lq.deepseek.domain.entity.InterviewReport;
import com.lq.deepseek.domain.entity.InterviewSession;
import com.lq.deepseek.domain.entity.InterviewTurn;
import com.lq.deepseek.domain.entity.LongTermMemory;
import com.lq.deepseek.domain.mapper.FileAssetMapper;
import com.lq.deepseek.domain.mapper.InterviewReportMapper;
import com.lq.deepseek.domain.mapper.InterviewSessionMapper;
import com.lq.deepseek.domain.mapper.InterviewTurnMapper;
import com.lq.deepseek.domain.mapper.LongTermMemoryMapper;
import com.lq.deepseek.dto.InterviewDtos;
import com.lq.deepseek.gateway.AiInvocationGateway;
import com.lq.deepseek.gateway.model.AgentInvokeCommand;
import com.lq.deepseek.gateway.model.AgentInvokeResult;
import com.lq.deepseek.service.InterviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
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
 *   <li><b>出题须经网关</b>：所有 AI 交互（首题 / 追问 / 终评 / 下一题）统一走 {@code AiInvocationGateway}
 *       的 INTERVIEW 业务类型，不绕过治理层（去重 / 重试 / 计量 / 审计）；</li>
 *   <li><b>失败不卡死</b>：网关不可用时回退确定性题目，让面试能继续推进，而不是整场开不出来；</li>
 *   <li><b>分数可追溯</b>：终态报告落 {@code interview_reports}，界面上的分数永远能追到具体调用。</li>
 * </ol>
 *
 * <p>出题引擎（P0-6）以 agent 的 {@code questionPlan} 为权威：{@code start} 把计划与当前进度写入
 * {@code stateSnapshot}，{@code answer}/{@code next} 据此推进到下一题，{@code finish} 用逐题问答对聚合评分。
 * BFF 与 agent 的契约是 <b>mode 字段</b>（START / ANSWER / NEXT / FINISH），而非旧的 stage 字段。
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
    private final LongTermMemoryMapper longTermMemoryMapper;
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
        String resumeText = profile.isEmpty() ? "" : writeJson(profile);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "START");
        payload.put("jobTitle", jobTitle);
        payload.put("resumeText", resumeText);
        payload.put("weakPoints", List.of());

        AgentInvokeResult result = callGateway(userId, session.getId(), "START", payload, OPEN_FREEZE_CREDIT);
        Map<String, Object> output = result != null && result.succeeded() ? result.getOutput() : null;

        String opening;
        List<Map<String, Object>> plan;
        String firstSkill;
        int questionIndex;
        List<String> weakPoints;
        if (output != null && output.get("question") != null) {
            opening = strOf(output.get("question"), FALLBACK_OPENING);
            plan = snapPlans(output.get("questionPlan"));
            firstSkill = strOf(output.get("skill"), null);
            questionIndex = intOf(output.get("questionIndex"), 1);
            weakPoints = strListOf(output.get("weakPointsTracking"));
        } else {
            log.warn("面试首题生成降级为本地题目 userId={} sessionId={}", userId, session.getId());
            opening = FALLBACK_OPENING;
            plan = List.of();
            firstSkill = null;
            questionIndex = 1;
            weakPoints = List.of();
        }

        Map<String, Object> openingEval = new LinkedHashMap<>();
        openingEval.put("questionIndex", questionIndex);
        openingEval.put("isFollowUp", false);
        appendTurn(session.getId(), 1, ROLE_INTERVIEWER, opening, null, firstSkill, openingEval);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("questionPlan", plan);
        snapshot.put("currentSkill", firstSkill);
        snapshot.put("questionIndex", questionIndex);
        snapshot.put("weakPoints", weakPoints);
        snapshot.put("followUpCount", 0);
        session.setStateSnapshot(snapshot);
        sessionMapper.updateById(session);

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
    // 作答 / 下一题 / 结束
    // ------------------------------------------------------------------

    @Override
    public InterviewDtos.InterviewTurn answer(Long userId, Long sessionId, InterviewDtos.AnswerRequest request) {
        InterviewSession session = requireOwned(userId, sessionId);
        if (STATUS_FINISHED.equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "面试已结束，无法继续作答");
        }
        Map<String, Object> snap = session.getStateSnapshot() != null ? session.getStateSnapshot() : Map.of();
        String currentSkill = strOf(snap.get("currentSkill"), null);
        int questionIndex = intOf(snap.get("questionIndex"), 1);
        List<String> weakPoints = strListOf(snap.get("weakPoints"));
        int followUpCount = intOf(snap.get("followUpCount"), 0);
        List<Map<String, Object>> plan = snapPlans(snap.get("questionPlan"));

        int maxSeq = turnMapper.selectMaxSeq(sessionId);
        InterviewTurn currentQuestion = latestInterviewerTurn(sessionId);
        if (currentQuestion == null) {
            Map<String, Object> fbEval = new LinkedHashMap<>();
            fbEval.put("questionIndex", 1);
            fbEval.put("isFollowUp", false);
            currentQuestion = appendTurn(session.getId(), maxSeq + 1, ROLE_INTERVIEWER,
                    FALLBACK_OPENING, null, null, fbEval);
            maxSeq = maxSeq + 1;
        }
        String askedQuestion = currentQuestion.getContent();
        String askedSkill = currentQuestion.getSkill() != null ? currentQuestion.getSkill() : currentSkill;

        InterviewTurn candidateTurn = appendTurn(session.getId(), maxSeq + 1, ROLE_CANDIDATE,
                request.getContent(), null, null, null);

        List<Map<String, Object>> history = buildHistory(sessionId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "ANSWER");
        payload.put("jobTitle", session.getTitle());
        payload.put("answer", request.getContent());
        payload.put("question", askedQuestion);
        payload.put("skill", askedSkill);
        payload.put("weakPoints", weakPoints);
        payload.put("history", history);
        payload.put("followUpCount", followUpCount);
        payload.put("questionPlan", plan);

        AgentInvokeResult result = callGateway(userId, sessionId, "ANSWER", payload, ANSWER_FREEZE_CREDIT);
        Map<String, Object> out = result != null && result.succeeded() ? result.getOutput() : null;

        String nextQuestion;
        Integer turnScore;
        String nextSkill;
        boolean isFinished;
        List<String> hitKeywords;
        List<String> missedKeywords;
        boolean hasFollowUp;
        List<String> weakUpdate;
        if (out != null) {
            nextQuestion = strOf(out.get("question"), fallbackQuestion(questionIndex + 1));
            turnScore = intOf(out.get("score"));
            nextSkill = strOf(out.get("nextSkill"), null);
            isFinished = Boolean.TRUE.equals(out.get("isFinished"));
            hitKeywords = strListOf(out.get("matchedKeywords"));
            missedKeywords = strListOf(out.get("missingKeywords"));
            hasFollowUp = Boolean.TRUE.equals(out.get("hasFollowUp"));
            weakUpdate = strListOf(out.get("weakPointsUpdate"));
        } else {
            log.warn("面试追问降级为本地题目 userId={} sessionId={}", userId, sessionId);
            nextQuestion = fallbackQuestion(questionIndex + 1);
            turnScore = 70;
            nextSkill = null;
            isFinished = false;
            hitKeywords = List.of();
            missedKeywords = List.of();
            hasFollowUp = false;
            weakUpdate = weakPoints;
        }

        // 把评分与命中情况回填到当前题目（用于 buildHistory 透传 skill 与终评聚合）
        Map<String, Object> evalMeta = new LinkedHashMap<>();
        evalMeta.put("questionIndex", questionIndex);
        evalMeta.put("isFollowUp", false);
        evalMeta.put("matchedKeywords", hitKeywords);
        evalMeta.put("missingKeywords", missedKeywords);
        evalMeta.put("hasFollowUp", hasFollowUp);
        currentQuestion.setScore(turnScore == null ? null : BigDecimal.valueOf(turnScore));
        currentQuestion.setEvalMeta(evalMeta);
        turnMapper.updateById(currentQuestion);

        Map<String, Object> nextEval = new LinkedHashMap<>();
        nextEval.put("questionIndex", isFinished ? questionIndex : questionIndex + 1);
        nextEval.put("isFollowUp", hasFollowUp);
        appendTurn(session.getId(), maxSeq + 2, ROLE_INTERVIEWER, nextQuestion, null, nextSkill, nextEval);

        // 推进出题进度快照
        Map<String, Object> newSnap = new LinkedHashMap<>(snap);
        if (hasFollowUp) {
            newSnap.put("followUpCount", followUpCount + 1);
        } else {
            newSnap.put("followUpCount", 0);
            newSnap.put("questionIndex", questionIndex + 1);
            newSnap.put("currentSkill", nextSkill);
        }
        newSnap.put("weakPoints", weakUpdate);
        session.setStateSnapshot(newSnap);
        session.setTurnCount((session.getTurnCount() == null ? 0 : session.getTurnCount()) + 1);
        sessionMapper.updateById(session);

        InterviewDtos.MessageVO userVo = toMessageVO(candidateTurn);
        userVo.setQuestionScore(turnScore);
        userVo.setHitKeywords(hitKeywords);
        userVo.setMissedKeywords(missedKeywords);
        userVo.setIsFollowUp(hasFollowUp);
        userVo.setQuestionIndex(questionIndex);

        InterviewDtos.InterviewTurn vo = new InterviewDtos.InterviewTurn();
        vo.setUserMessage(userVo);
        vo.setAiMessage(toMessageVO(latestInterviewerTurn(sessionId)));
        vo.setTranscript(null);
        return vo;
    }

    @Override
    public InterviewDtos.MessageVO nextQuestion(Long userId, Long sessionId) {
        InterviewSession session = requireOwned(userId, sessionId);
        if (STATUS_FINISHED.equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "面试已结束，无法继续作答");
        }
        Map<String, Object> snap = session.getStateSnapshot() != null ? session.getStateSnapshot() : Map.of();
        int questionIndex = intOf(snap.get("questionIndex"), 1);
        String currentSkill = strOf(snap.get("currentSkill"), null);
        List<String> weakPoints = strListOf(snap.get("weakPoints"));
        List<Map<String, Object>> plan = snapPlans(snap.get("questionPlan"));

        List<Map<String, Object>> history = buildHistory(sessionId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "NEXT");
        payload.put("jobTitle", session.getTitle());
        payload.put("questionPlan", plan);
        payload.put("history", history);
        payload.put("weakPoints", weakPoints);
        payload.put("skill", currentSkill);

        AgentInvokeResult result = callGateway(userId, sessionId, "NEXT", payload, ANSWER_FREEZE_CREDIT);
        Map<String, Object> out = result != null && result.succeeded() ? result.getOutput() : null;

        String nextQuestion;
        String nextSkill;
        boolean isFinished;
        int nextIndex;
        if (out != null) {
            nextQuestion = strOf(out.get("question"), fallbackQuestion(questionIndex + 1));
            nextSkill = strOf(out.get("skill"), null);
            isFinished = Boolean.TRUE.equals(out.get("isFinished"));
            nextIndex = intOf(out.get("questionIndex"), questionIndex + 1);
        } else {
            log.warn("面试下一题降级为本地题目 userId={} sessionId={}", userId, sessionId);
            nextQuestion = fallbackQuestion(questionIndex + 1);
            nextSkill = null;
            isFinished = false;
            nextIndex = questionIndex + 1;
        }

        int maxSeq = turnMapper.selectMaxSeq(sessionId);
        Map<String, Object> nextEval = new LinkedHashMap<>();
        nextEval.put("questionIndex", nextIndex);
        nextEval.put("isFollowUp", false);
        InterviewTurn aiTurn = appendTurn(session.getId(), maxSeq + 1, ROLE_INTERVIEWER,
                nextQuestion, null, nextSkill, nextEval);

        Map<String, Object> newSnap = new LinkedHashMap<>(snap);
        newSnap.put("currentSkill", nextSkill);
        newSnap.put("questionIndex", nextIndex);
        if (isFinished) {
            newSnap.put("currentSkill", nextSkill);
        }
        session.setStateSnapshot(newSnap);
        sessionMapper.updateById(session);

        return toMessageVO(aiTurn);
    }

    @Override
    public InterviewDtos.InterviewReport finish(Long userId, Long sessionId) {
        InterviewSession session = requireOwned(userId, sessionId);
        InterviewReport existing = latestReport(sessionId);
        if (existing != null) {
            return toReportVO(existing);
        }

        Map<String, Object> snap = session.getStateSnapshot() != null ? session.getStateSnapshot() : Map.of();
        List<String> weakPoints = strListOf(snap.get("weakPoints"));
        List<Map<String, Object>> history = buildHistory(sessionId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "FINISH");
        payload.put("jobTitle", session.getTitle());
        payload.put("weakPoints", weakPoints);
        payload.put("history", history);

        AgentInvokeResult result = callGateway(userId, sessionId, "FINISH", payload, FINISH_FREEZE_CREDIT);
        Map<String, Object> out = result != null && result.succeeded() ? result.getOutput() : null;

        Integer score;
        Map<String, Object> dimensions;
        List<String> weakUpdate;
        String suggestion;
        if (out != null) {
            score = intOf(out.get("totalScore"));
            dimensions = mapOf(out.get("dimensions"));
            weakUpdate = strListOf(out.get("weakPointsUpdate"));
            suggestion = strOf(out.get("report"), null);
        } else {
            log.warn("面试终评降级为本地报告 userId={} sessionId={}", userId, sessionId);
            score = 60;
            dimensions = Map.of("communication", 60, "skill", 60);
            weakUpdate = List.of();
            suggestion = "本次面试已完成，评分服务暂不可用，建议稍后在「面试记录」中查看完整评估。";
        }

        InterviewReport report = new InterviewReport();
        report.setUserId(userId);
        report.setSessionId(sessionId);
        report.setScore(score);
        report.setDimensions(dimensions);
        report.setWeakPoints(weakUpdate);
        report.setSuggestion(suggestion);
        reportMapper.insert(report);

        // P0-7: 薄弱点回写用户画像 —— 每个薄弱点插入 PENDING 长期记忆
        if (weakUpdate != null && !weakUpdate.isEmpty()) {
            for (String wp : weakUpdate) {
                LongTermMemory memory = new LongTermMemory();
                memory.setUserId(userId);
                memory.setContent("面试薄弱点: " + wp);
                memory.setStatus("PENDING");
                longTermMemoryMapper.insert(memory);
            }
            log.info("面试薄弱点已回写画像 userId={} sessionId={} count={}", userId, sessionId, weakUpdate.size());
        }

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

    private InterviewTurn appendTurn(Long sessionId, int seq, String role, String content,
                                     Integer score, String skill, Map<String, Object> evalMeta) {
        InterviewTurn turn = new InterviewTurn();
        turn.setSessionId(sessionId);
        turn.setSeq(seq);
        turn.setRole(role);
        turn.setContent(content);
        turn.setStage(ROLE_INTERVIEWER.equals(role) ? "QUESTION" : "ANSWER");
        turn.setScore(score == null ? null : BigDecimal.valueOf(score));
        turn.setSkill(skill);
        turn.setEvalMeta(evalMeta);
        turnMapper.insert(turn);
        return turn;
    }

    private InterviewTurn latestInterviewerTurn(Long sessionId) {
        return turnMapper.selectOne(new LambdaQueryWrapper<InterviewTurn>()
                .eq(InterviewTurn::getSessionId, sessionId)
                .eq(InterviewTurn::getRole, ROLE_INTERVIEWER)
                .orderByDesc(InterviewTurn::getSeq)
                .last("LIMIT 1"));
    }

    private List<Map<String, Object>> buildHistory(Long sessionId) {
        List<InterviewTurn> turns = turnMapper.selectList(new LambdaQueryWrapper<InterviewTurn>()
                .eq(InterviewTurn::getSessionId, sessionId)
                .orderByAsc(InterviewTurn::getSeq)
                .last("LIMIT 500"));
        List<Map<String, Object>> history = new ArrayList<>(turns.size());
        for (int i = 0; i < turns.size(); i++) {
            InterviewTurn t = turns.get(i);
            if (!ROLE_INTERVIEWER.equals(t.getRole()) || t.getScore() == null) {
                continue;
            }
            InterviewTurn candidate = (i + 1 < turns.size()
                    && ROLE_CANDIDATE.equals(turns.get(i + 1).getRole())) ? turns.get(i + 1) : null;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("question", t.getContent());
            item.put("answer", candidate != null ? candidate.getContent() : "");
            item.put("score", t.getScore().intValue());
            item.put("skill", t.getSkill());
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

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "";
        }
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
        if (turn.getScore() != null) {
            vo.setQuestionScore(turn.getScore().intValue());
        }
        Map<String, Object> meta = turn.getEvalMeta();
        if (meta != null) {
            if (meta.get("questionIndex") != null) {
                vo.setQuestionIndex(intOf(meta.get("questionIndex")));
            }
            if (meta.get("isFollowUp") != null) {
                vo.setIsFollowUp(Boolean.TRUE.equals(meta.get("isFollowUp")));
            }
            if (meta.get("matchedKeywords") != null) {
                vo.setHitKeywords(strListOf(meta.get("matchedKeywords")));
            }
            if (meta.get("missingKeywords") != null) {
                vo.setMissedKeywords(strListOf(meta.get("missingKeywords")));
            }
        }
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

    private static Integer intOf(Object value, int fallback) {
        Integer v = intOf(value);
        return v != null ? v : fallback;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
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
    private static List<Map<String, Object>> snapPlans(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
