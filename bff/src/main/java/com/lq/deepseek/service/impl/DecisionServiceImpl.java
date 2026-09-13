package com.lq.deepseek.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lq.deepseek.common.BusinessException;
import com.lq.deepseek.common.ErrorCode;
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
import com.lq.deepseek.service.DecisionService;
import com.lq.deepseek.service.support.ContextAssembler;
import com.lq.deepseek.service.support.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JD 分析实现。
 *
 * <p>三条硬规则，决定了这个类为什么这么写：
 * <ol>
 *   <li><b>分数必须可追</b>：每次分析落一行明细并记 run_id，界面上任何一个分数都能追到具体 AI 调用；</li>
 *   <li><b>不重复扣费</b>：同一份 JD + 同一份简历的重复点击由网关层按输入摘要回放，本类不做缓存判断；</li>
 *   <li><b>失败要可见</b>：分析失败把会话置 FAILED 并回传原因，不把"AI 挂了"渲染成"你匹配度 0 分"。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionServiceImpl implements DecisionService {

    static final String SCENE = "JD_MATCH";

    static final String BIZ_DECIDE = "DECIDE";
    static final String BIZ_PREVIEW = "DECIDE_PREVIEW";
    static final String BIZ_PARSE = "RESUME_PARSE";
    static final String BIZ_RAG = "RAG_SEARCH";

    static final String SPEC_DECIDE = "jd-match-v1";
    static final String SPEC_PREVIEW = "jd-preview-v1";
    static final String SPEC_ASK = "decision-ask-v1";
    static final String SPEC_JD_PARSE = "jd-extract-v1";

    static final String BIZ_ASSET_JD = "JD";
    static final String BIZ_ASSET_RESUME = "RESUME";

    static final String STATUS_CREATED = "CREATED";
    static final String STATUS_RUNNING = "RUNNING";
    static final String STATUS_FINISHED = "FINISHED";
    static final String STATUS_FAILED = "FAILED";

    static final String ROLE_SYSTEM = "SYSTEM";
    static final String ROLE_USER = "USER";
    static final String ROLE_ASSISTANT = "ASSISTANT";

    static final long DECIDE_FREEZE_CREDIT = 12L;
    static final long PREVIEW_FREEZE_CREDIT = 4L;
    static final long ASK_FREEZE_CREDIT = 6L;
    static final long JD_PARSE_FREEZE_CREDIT = 4L;
    static final long DECIDE_TIMEOUT_MS = 120_000L;
    static final long ASK_TIMEOUT_MS = 60_000L;

    static final Set<String> JD_ALLOWED_EXTS = Set.of("pdf", "docx", "md", "txt");

    static final int JD_EXCERPT_LIMIT = 4000;
    static final int JD_TEXT_LIMIT = 20_000;
    static final int CHUNK_SIZE = 420;
    static final int CHUNK_OVERLAP = 80;
    static final int ASK_DOC_LIMIT = 40;

    private final DecisionSessionMapper sessionMapper;
    private final DecisionAnalysisMapper analysisMapper;
    private final DecisionMessageMapper messageMapper;
    private final FileAssetMapper fileAssetMapper;
    private final AiInvocationGateway gateway;
    private final FileStorageService fileStorage;
    private final ObjectMapper objectMapper;
    private final ContextAssembler contextAssembler;

    // -----------------------------------------------------------------------
    // JD 上传
    // -----------------------------------------------------------------------

    @Override
    public DecisionDtos.JdUploadVO uploadJd(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_EMPTY);
        }
        String fileName = StringUtils.cleanPath(
                Objects.requireNonNullElse(file.getOriginalFilename(), "jd.txt"));
        String ext = fileStorage.extensionOf(fileName);
        if (!JD_ALLOWED_EXTS.contains(ext)) {
            throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED,
                    "JD 仅支持 pdf / docx / md / txt，当前为 ." + (ext.isEmpty() ? "未知" : ext));
        }
        byte[] bytes = readBytes(file);
        String sha256 = fileStorage.sha256(bytes);

        FileAsset asset = fileAssetMapper.selectActiveBySha(userId, sha256, BIZ_ASSET_JD);
        if (asset != null && "SUCCESS".equals(asset.getParseStatus()) && asset.getParseResult() != null
                && StringUtils.hasText(strOf(asset.getParseResult().get("text"), null))) {
            log.info("JD 命中既有资产 userId={} assetId={}", userId, asset.getId());
            return toJdVO(asset, true);
        }
        if (asset == null) {
            asset = new FileAsset();
            asset.setUserId(userId);
            asset.setBizType(BIZ_ASSET_JD);
            asset.setFileName(fileName);
            asset.setContentType(file.getContentType());
            asset.setSizeBytes((long) bytes.length);
            asset.setSha256(sha256);
            asset.setParseStatus("PENDING");
            asset.setObjectKey(fileStorage.store(userId, sha256, ext, bytes));
            fileAssetMapper.insert(asset);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "JD_PARSE");
        payload.put("fileName", fileName);
        payload.put("fileBase64", Base64.getEncoder().encodeToString(bytes));
        payload.put("sizeBytes", bytes.length);

        try {
            AgentInvokeResult result = gateway.invoke(AgentInvokeCommand.builder()
                    .bizType(BIZ_PARSE)
                    .bizId(asset.getId())
                    .userId(userId)
                    .stage("JD_PARSE")
                    .specHash(SPEC_JD_PARSE)
                    .payload(payload)
                    .expectedCredit(JD_PARSE_FREEZE_CREDIT)
                    .build());
            if (!result.succeeded()) {
                markAssetFailed(asset, Objects.requireNonNullElse(result.getErrorMsg(), "JD 文本抽取失败"));
                return toJdVO(asset, false);
            }
            fileAssetMapper.updateParseState(asset.getId(), "SUCCESS", toJson(result.getOutput()), null);
            asset.setParseStatus("SUCCESS");
            asset.setParseResult(result.getOutput());
        } catch (BusinessException e) {
            markAssetFailed(asset, e.getMessage());
            // 额度不足属于用户可自行解决的失败，原样上抛，不降级成"抽取失败"
            if (e.getErrorCode() == ErrorCode.INSUFFICIENT_CREDIT) {
                throw e;
            }
            log.warn("JD 抽取失败 assetId={} reason={}", asset.getId(), e.getMessage());
        }
        return toJdVO(asset, false);
    }

    // -----------------------------------------------------------------------
    // 预览 / 正式分析
    // -----------------------------------------------------------------------

    @Override
    public DecisionDtos.PreviewVO preview(Long userId, DecisionDtos.PreviewRequest request) {
        Map<String, Object> profile = loadResumeProfile(userId, request.getResumeAssetId());
        String jdText = resolveJdText(userId, request.getJdText(), request.getJdAssetId(), null);
        requireJdText(jdText);

        AgentInvokeResult result = gateway.invoke(AgentInvokeCommand.builder()
                .bizType(BIZ_PREVIEW)
                .userId(userId)
                .stage("PREVIEW")
                .specHash(SPEC_PREVIEW)
                .payload(decidePayload(jdText, profile, null))
                .expectedCredit(PREVIEW_FREEZE_CREDIT)
                .timeoutMs(DECIDE_TIMEOUT_MS)
                .build());
        if (!result.succeeded()) {
            throw new BusinessException(ErrorCode.AGENT_STAGE_FAILED,
                    Objects.requireNonNullElse(result.getErrorMsg(), "预览失败，请稍后重试"));
        }
        Map<String, Object> output = result.getOutput();
        DecisionDtos.PreviewVO vo = new DecisionDtos.PreviewVO();
        vo.setRunId(result.getRunId());
        vo.setScore(intOf(output.get("score")));
        vo.setScoreBand(strOf(output.get("scoreBand"), band(intOf(output.get("score")))));
        vo.setConclusion(strOf(output.get("conclusion"), null));
        vo.setCoveredSkills(strListOf(output.get("coveredSkills")));
        vo.setMissingSkills(strListOf(output.get("missingSkills")));
        vo.setWeakPointsHit(strListOf(output.get("weakPointsHit")));
        vo.setRisks(strListOf(output.get("risks")));
        vo.setAdvice(strOf(output.get("advice"), null));
        vo.setPreview(Boolean.TRUE);
        vo.setSteps(stepListOf(output.get("steps")));
        vo.setCostCredit(result.getCostCredit());
        vo.setFlightMode(result.getFlightMode());
        return vo;
    }

    @Override
    public DecisionDtos.SessionDetailVO analyze(Long userId, DecisionDtos.AnalyzeRequest request) {
        Map<String, Object> profile = loadResumeProfile(userId, request.getResumeAssetId());
        String jdText = resolveJdText(userId, request.getJdText(), request.getJdAssetId(),
                request.getSessionId() == null ? null : requireSession(userId, request.getSessionId()));
        requireJdText(jdText);
        if (jdText.length() > JD_TEXT_LIMIT) {
            jdText = jdText.substring(0, JD_TEXT_LIMIT);
        }

        DecisionSession session = request.getSessionId() == null
                ? createSession(userId, request, jdText)
                : bindSession(userId, request, jdText);
        sessionMapper.markStatus(session.getId(), STATUS_RUNNING);

        // Context 治理：JD + 简历画像打包为可追踪快照（证据分层 + token 预算裁剪 + degraded 标记），
        // 会话挂 snapshotId，agent 负载带 contextSnapshotId，"这次分析看到了什么"全程可审计
        var contextSnapshot = contextAssembler.assemble(userId, session.getId(), BIZ_DECIDE, List.of(
                new ContextAssembler.Material("jd", ContextAssembler.EvidenceLevel.HIGH,
                        jdText, Map.of("jdAssetId", Objects.requireNonNullElse(request.getJdAssetId(), 0L))),
                new ContextAssembler.Material("resume", ContextAssembler.EvidenceLevel.HIGH,
                        profileDigest(profile), Map.of("resumeAssetId", request.getResumeAssetId()))
        ), null);
        DecisionSession snapshotPatch = new DecisionSession();
        snapshotPatch.setId(session.getId());
        snapshotPatch.setSnapshotId(contextSnapshot.getId());
        sessionMapper.updateById(snapshotPatch);
        if (Boolean.TRUE.equals(contextSnapshot.getDegraded())) {
            log.warn("JD 分析上下文降级 sessionId={} 原因={}", session.getId(), contextSnapshot.getDegradedReason());
        }

        AgentInvokeResult result;
        try {
            result = gateway.invoke(AgentInvokeCommand.builder()
                    .bizType(BIZ_DECIDE)
                    .bizId(session.getId())
                    .userId(userId)
                    .stage("MATCH")
                    .specHash(SPEC_DECIDE)
                    .payload(decidePayload(jdText, profile, contextSnapshot.getId()))
                    .expectedCredit(DECIDE_FREEZE_CREDIT)
                    .timeoutMs(DECIDE_TIMEOUT_MS)
                    .build());
        } catch (BusinessException e) {
            sessionMapper.markStatus(session.getId(), STATUS_FAILED);
            throw e;
        }
        if (!result.succeeded()) {
            sessionMapper.markStatus(session.getId(), STATUS_FAILED);
            throw new BusinessException(ErrorCode.AGENT_STAGE_FAILED,
                    Objects.requireNonNullElse(result.getErrorMsg(), "JD 分析失败，请稍后重试"));
        }

        DecisionAnalysis analysis;
        try {
            analysis = persistAnalysis(userId, session, request, result);
            String title = StringUtils.hasText(request.getTitle()) ? request.getTitle() : session.getTitle();
            sessionMapper.markAnalyzed(session.getId(), STATUS_FINISHED, result.getRunId(), analysis.getId(),
                    analysis.getScore(), title, request.getJobTitle());
            appendMessages(session.getId(), analysis);
        } catch (Exception e) {
            // 落库阶段失败也要可见：把会话置 FAILED 并回传原因，避免"AI 挂了"被渲染成卡在 RUNNING。
            log.error("JD 分析落库失败 userId={} sessionId={} runId={}",
                    userId, session.getId(), result.getRunId(), e);
            sessionMapper.markStatus(session.getId(), STATUS_FAILED);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "JD 分析结果落库失败，请稍后重试", e);
        }

        log.info("JD 分析完成 userId={} sessionId={} score={} runId={}",
                userId, session.getId(), analysis.getScore(), result.getRunId());
        return detail(userId, session.getId());
    }

    // -----------------------------------------------------------------------
    // 查询
    // -----------------------------------------------------------------------

    @Override
    public List<DecisionDtos.SessionBrief> listSessions(Long userId) {
        List<DecisionSession> sessions = sessionMapper.selectList(new LambdaQueryWrapper<DecisionSession>()
                .eq(DecisionSession::getUserId, userId)
                .eq(DecisionSession::getScene, SCENE)
                .orderByDesc(DecisionSession::getUpdatedAt)
                .last("LIMIT 50"));
        Map<Long, Integer> counts = analysisMapper.countByUser(userId).stream()
                .collect(Collectors.toMap(
                        row -> longOf(row.get("sessionId")),
                        row -> intOf(row.get("total")),
                        (left, right) -> left));

        List<DecisionDtos.SessionBrief> briefs = new ArrayList<>(sessions.size());
        for (DecisionSession session : sessions) {
            DecisionDtos.SessionBrief brief = new DecisionDtos.SessionBrief();
            brief.setSessionId(session.getId());
            brief.setTitle(session.getTitle());
            brief.setJobTitle(session.getJobTitle());
            brief.setStatus(session.getStatus());
            brief.setLatestScore(session.getLatestScore());
            brief.setLatestAnalysisId(session.getLatestAnalysisId());
            brief.setAnalysisCount(counts.getOrDefault(session.getId(), 0));
            brief.setUpdatedAt(session.getUpdatedAt());
            briefs.add(brief);
        }
        return briefs;
    }

    @Override
    public DecisionDtos.SessionDetailVO detail(Long userId, Long sessionId) {
        DecisionSession session = requireSession(userId, sessionId);
        List<DecisionAnalysis> analyses = analysisMapper.selectList(new LambdaQueryWrapper<DecisionAnalysis>()
                .eq(DecisionAnalysis::getSessionId, sessionId)
                .orderByDesc(DecisionAnalysis::getCreatedAt)
                .last("LIMIT 20"));
        List<DecisionMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<DecisionMessage>()
                .eq(DecisionMessage::getSessionId, sessionId)
                .orderByAsc(DecisionMessage::getSeq)
                .last("LIMIT 200"));

        DecisionDtos.SessionDetailVO vo = new DecisionDtos.SessionDetailVO();
        vo.setSessionId(session.getId());
        vo.setTitle(session.getTitle());
        vo.setJobTitle(session.getJobTitle());
        vo.setStatus(session.getStatus());
        vo.setLatestScore(session.getLatestScore());
        vo.setResumeAssetId(session.getResumeAssetId());
        vo.setJdAssetId(session.getJdAssetId());
        vo.setJdCharCount(session.getJdText() == null ? 0 : session.getJdText().length());
        vo.setCreatedAt(session.getCreatedAt());
        vo.setUpdatedAt(session.getUpdatedAt());
        vo.setAnalyses(analyses.stream().map(this::toAnalysisVO).toList());
        vo.setLatestAnalysis(analyses.isEmpty() ? null : toAnalysisVO(analyses.get(0)));
        vo.setMessages(messages.stream().map(this::toMessageVO).toList());
        return vo;
    }

    @Override
    public DecisionDtos.AskVO ask(Long userId, Long sessionId, DecisionDtos.AskRequest request) {
        DecisionSession session = requireSession(userId, sessionId);
        String question = request.getQuestion().trim();

        List<Map<String, Object>> documents = buildDocuments(userId, session);
        AgentInvokeResult result = gateway.invoke(AgentInvokeCommand.builder()
                .bizType(BIZ_RAG)
                .bizId(sessionId)
                .userId(userId)
                .stage("ASK")
                .specHash(SPEC_ASK)
                .payload(Map.of("query", question, "topK", 5, "documents", documents))
                .expectedCredit(ASK_FREEZE_CREDIT)
                .timeoutMs(ASK_TIMEOUT_MS)
                .build());
        if (!result.succeeded()) {
            throw new BusinessException(ErrorCode.AGENT_STAGE_FAILED,
                    Objects.requireNonNullElse(result.getErrorMsg(), "追问失败，请稍后重试"));
        }

        Map<String, Object> output = result.getOutput();
        String answer = strOf(output.get("answer"), "未检索到可用片段，无法回答。");
        appendMessage(sessionId, ROLE_USER, question);
        appendMessage(sessionId, ROLE_ASSISTANT, answer);

        DecisionDtos.AskVO vo = new DecisionDtos.AskVO();
        vo.setRunId(result.getRunId());
        vo.setAnswer(answer);
        vo.setHits(hitListOf(output.get("hits")));
        vo.setRecallGap(Boolean.TRUE.equals(output.get("recallGap")));
        vo.setCostCredit(result.getCostCredit());
        vo.setFlightMode(result.getFlightMode());
        vo.setMessages(List.of(
                message(0, ROLE_USER, question, null),
                message(0, ROLE_ASSISTANT, answer, null)));
        return vo;
    }

    @Override
    public void archive(Long userId, Long sessionId) {
        requireSession(userId, sessionId);
        // 逻辑删除：历史分析与会话消息保留，便于"误删后找回"和后台对账
        sessionMapper.deleteById(sessionId);
        log.info("JD 分析会话已归档 userId={} sessionId={}", userId, sessionId);
    }

    // -----------------------------------------------------------------------
    // 内部实现
    // -----------------------------------------------------------------------

    private Map<String, Object> decidePayload(String jdText, Map<String, Object> profile, Long contextSnapshotId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jdText", jdText);
        payload.put("profile", profile);
        // 简历原文由画像字段拼回：agent 侧做关键词覆盖判定时需要可检索的原始文本
        payload.put("resumeText", profileDigest(profile));
        if (contextSnapshotId != null) {
            payload.put("contextSnapshotId", contextSnapshotId);
        }
        return payload;
    }

    private DecisionSession createSession(Long userId, DecisionDtos.AnalyzeRequest request, String jdText) {
        DecisionSession session = new DecisionSession();
        session.setUserId(userId);
        session.setScene(SCENE);
        session.setStatus(STATUS_CREATED);
        session.setTitle(resolveTitle(request, jdText));
        session.setJobTitle(request.getJobTitle());
        session.setJdText(jdText);
        session.setResumeAssetId(request.getResumeAssetId());
        session.setJdAssetId(request.getJdAssetId());
        sessionMapper.insert(session);
        return session;
    }

    /** 已存在会话：把新传入的 JD / 简历绑定更新到会话上（空值不动，避免覆盖）。 */
    private DecisionSession bindSession(Long userId, DecisionDtos.AnalyzeRequest request, String jdText) {
        DecisionSession session = requireSession(userId, request.getSessionId());
        DecisionSession patch = new DecisionSession();
        patch.setId(session.getId());
        if (request.getJdAssetId() != null) {
            patch.setJdAssetId(request.getJdAssetId());
        }
        if (request.getResumeAssetId() != null) {
            patch.setResumeAssetId(request.getResumeAssetId());
        }
        if (StringUtils.hasText(request.getJobTitle())) {
            patch.setJobTitle(request.getJobTitle());
        }
        if (!jdText.equals(session.getJdText())) {
            patch.setJdText(jdText);
        }
        sessionMapper.updateById(patch);
        return session;
    }

    private DecisionAnalysis persistAnalysis(Long userId, DecisionSession session,
                                             DecisionDtos.AnalyzeRequest request, AgentInvokeResult result) {
        Map<String, Object> output = result.getOutput();
        DecisionAnalysis analysis = new DecisionAnalysis();
        analysis.setSessionId(session.getId());
        analysis.setUserId(userId);
        analysis.setMode("FULL");
        analysis.setRunId(result.getRunId());
        analysis.setResumeAssetId(session.getResumeAssetId());
        analysis.setJdAssetId(session.getJdAssetId());
        analysis.setJdExcerpt(abbreviate(session.getJdText(), JD_EXCERPT_LIMIT));
        analysis.setScore(intOf(output.get("score")));
        analysis.setScoreBand(strOf(output.get("scoreBand"), band(analysis.getScore())));
        analysis.setConclusion(strOf(output.get("conclusion"), null));
        analysis.setDimensions(mapOf(output.get("dimensions")));
        analysis.setRequiredSkills(strListOf(output.get("requiredSkills")));
        analysis.setCoveredSkills(strListOf(output.get("coveredSkills")));
        analysis.setMissingSkills(strListOf(output.get("missingSkills")));
        analysis.setWeakPointsHit(strListOf(output.get("weakPointsHit")));
        analysis.setHardRequirements(strListOf(output.get("hardRequirements")));
        analysis.setRisks(strListOf(output.get("risks")));
        analysis.setTodos(strListOf(output.get("todos")));
        analysis.setAdvice(abbreviate(strOf(output.get("advice"), null), JD_EXCERPT_LIMIT));
        analysis.setCostCredit(result.getCostCredit());
        analysis.setLatencyMs((int) Math.min(result.getLatencyMs(), Integer.MAX_VALUE));
        analysisMapper.insert(analysis);
        return analysis;
    }

    /**
     * 时间线落两条消息：一条结论（分块展示的锚点）、一条建议。
     *
     * <p>不把完整明细塞进消息体——明细已经结构化落库，消息只负责让历史会话"读得懂"。
     */
    private void appendMessages(Long sessionId, DecisionAnalysis analysis) {
        String headline = "【JD 分析完成】匹配度 " + Objects.toString(analysis.getScore(), "-")
                + " 分（" + Objects.toString(analysis.getScoreBand(), "-") + "）"
                + (StringUtils.hasText(analysis.getConclusion()) ? " · " + analysis.getConclusion() : "");
        appendMessage(sessionId, ROLE_SYSTEM, headline);
        if (StringUtils.hasText(analysis.getAdvice())) {
            appendMessage(sessionId, ROLE_ASSISTANT, analysis.getAdvice());
        }
    }

    private void appendMessage(Long sessionId, String role, String content) {
        DecisionMessage message = new DecisionMessage();
        message.setSessionId(sessionId);
        message.setSeq(messageMapper.selectMaxSeq(sessionId) + 1);
        message.setRole(role);
        message.setContent(content);
        messageMapper.insert(message);
    }

    /** 追问语料 = JD 全文分片 + 简历画像分片 + 最近一次分析结论，全部来自真实落库内容。 */
    private List<Map<String, Object>> buildDocuments(Long userId, DecisionSession session) {
        List<Map<String, Object>> documents = new ArrayList<>();
        String jdText = session.getJdText();
        if (StringUtils.hasText(jdText)) {
            addChunks(documents, "JD-" + session.getId(), "职位描述", jdText);
        }
        if (session.getResumeAssetId() != null) {
            try {
                Map<String, Object> profile = loadResumeProfile(userId, session.getResumeAssetId());
                addChunks(documents, "RESUME-" + session.getResumeAssetId(), "我的简历", profileDigest(profile));
            } catch (BusinessException e) {
                log.warn("追问语料缺失简历 userId={} assetId={} 原因={}", userId, session.getResumeAssetId(), e.getMessage());
            }
        }
        if (session.getLatestAnalysisId() != null) {
            DecisionAnalysis latest = analysisMapper.selectById(session.getLatestAnalysisId());
            if (latest != null) {
                StringBuilder sb = new StringBuilder();
                sb.append("匹配分 ").append(Objects.toString(latest.getScore(), "-"))
                        .append("，结论 ").append(Objects.toString(latest.getConclusion(), "-")).append('\n');
                sb.append("缺失技能：").append(String.join("、", nullSafe(latest.getMissingSkills()))).append('\n');
                sb.append("风险：").append(String.join("、", nullSafe(latest.getRisks()))).append('\n');
                sb.append("建议：").append(Objects.toString(latest.getAdvice(), "")).append('\n');
                sb.append("待补强：").append(String.join("、", nullSafe(latest.getTodos())));
                addChunks(documents, "ANALYSIS-" + latest.getId(), "最近一次分析结论", sb.toString());
            }
        }
        return documents.size() > ASK_DOC_LIMIT ? documents.subList(0, ASK_DOC_LIMIT) : documents;
    }

    private void addChunks(List<Map<String, Object>> documents, String docId, String title, String text) {
        int start = 0;
        int index = 1;
        while (start < text.length() && documents.size() < ASK_DOC_LIMIT) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            String chunk = text.substring(start, end).trim();
            if (StringUtils.hasText(chunk)) {
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("documentId", docId + "#" + index);
                doc.put("title", title);
                doc.put("content", chunk);
                documents.add(doc);
                index++;
            }
            if (end >= text.length()) {
                break;
            }
            start = Math.max(end - CHUNK_OVERLAP, start + 1);
        }
    }

    private String resolveJdText(Long userId, String inlineText, Long jdAssetId, DecisionSession session) {
        if (StringUtils.hasText(inlineText)) {
            return inlineText.trim();
        }
        if (jdAssetId != null) {
            FileAsset asset = requireOwnedAsset(userId, jdAssetId);
            String text = asset.getParseResult() == null ? null : strOf(asset.getParseResult().get("text"), null);
            if (!StringUtils.hasText(text)) {
                throw new BusinessException(ErrorCode.CONFLICT, "该 JD 文件尚未抽取成功，请重新上传");
            }
            return text.trim();
        }
        if (session != null && StringUtils.hasText(session.getJdText())) {
            return session.getJdText();
        }
        return null;
    }

    private void requireJdText(String jdText) {
        if (!StringUtils.hasText(jdText)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请粘贴 JD 文本或选择已上传的 JD 文件");
        }
    }

    private Map<String, Object> loadResumeProfile(Long userId, Long resumeAssetId) {
        if (resumeAssetId == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请先选择一份简历");
        }
        FileAsset asset = requireOwnedAsset(userId, resumeAssetId);
        if (!BIZ_ASSET_RESUME.equals(asset.getBizType())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "所选资产不是简历");
        }
        if (!"SUCCESS".equals(asset.getParseStatus()) || asset.getParseResult() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "该简历尚未解析成功，请先在简历中心补充内容");
        }
        return asset.getParseResult();
    }

    private FileAsset requireOwnedAsset(Long userId, Long assetId) {
        FileAsset asset = fileAssetMapper.selectById(assetId);
        // 他人资产按"不存在"处理，避免通过错误码探测资源
        if (asset == null || !Objects.equals(asset.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        return asset;
    }

    private DecisionSession requireSession(Long userId, Long sessionId) {
        if (sessionId == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "缺少会话 ID");
        }
        DecisionSession session = sessionMapper.selectById(sessionId);
        if (session == null || !Objects.equals(session.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "分析会话不存在");
        }
        return session;
    }

    @SuppressWarnings("unchecked")
    private String profileDigest(Map<String, Object> profile) {
        StringBuilder sb = new StringBuilder();
        Object summary = profile.get("summary");
        if (summary != null) {
            sb.append(summary).append('\n');
        }
        appendJoined(sb, "技能", profile.get("skills"));
        appendJoined(sb, "工作经历", profile.get("experiences"));
        appendJoined(sb, "项目经历", profile.get("projects"));
        appendJoined(sb, "教育经历", profile.get("education"));
        appendJoined(sb, "薄弱点", profile.get("weakPoints"));
        Object years = profile.get("experienceYears");
        if (years != null) {
            sb.append("工作年限：").append(years).append(" 年\n");
        }
        return sb.toString();
    }

    private void appendJoined(StringBuilder sb, String label, Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            sb.append(label).append("：");
            List<String> parts = new ArrayList<>(list.size());
            for (Object item : list) {
                parts.add(String.valueOf(item));
            }
            sb.append(String.join("；", parts)).append('\n');
        }
    }

    private String resolveTitle(DecisionDtos.AnalyzeRequest request, String jdText) {
        if (StringUtils.hasText(request.getTitle())) {
            return request.getTitle();
        }
        if (StringUtils.hasText(request.getJobTitle())) {
            return request.getJobTitle();
        }
        String head = jdText.strip().split("\n")[0];
        return head.length() > 40 ? head.substring(0, 40) : head;
    }

    private void markAssetFailed(FileAsset asset, String message) {
        String reason = StringUtils.hasText(message) ? message : "JD 文本抽取失败";
        fileAssetMapper.updateParseState(asset.getId(), "FAILED", null, reason);
        asset.setParseStatus("FAILED");
        asset.setErrorMsg(reason);
    }

    private DecisionDtos.JdUploadVO toJdVO(FileAsset asset, boolean deduplicated) {
        DecisionDtos.JdUploadVO vo = new DecisionDtos.JdUploadVO();
        vo.setAssetId(asset.getId());
        vo.setFileName(asset.getFileName());
        vo.setSha256(asset.getSha256());
        vo.setParseStatus(asset.getParseStatus());
        vo.setErrorMsg(asset.getErrorMsg());
        vo.setDeduplicated(deduplicated);
        Map<String, Object> parsed = asset.getParseResult();
        if (parsed != null) {
            vo.setCharCount(intOf(parsed.get("charCount")));
            vo.setHardRequirements(strListOf(parsed.get("hardRequirements")));
            vo.setSkills(strListOf(parsed.get("skills")));
            String text = strOf(parsed.get("text"), null);
            vo.setPreview(abbreviate(text, 300));
        }
        return vo;
    }

    private DecisionDtos.AnalysisVO toAnalysisVO(DecisionAnalysis analysis) {
        DecisionDtos.AnalysisVO vo = new DecisionDtos.AnalysisVO();
        vo.setAnalysisId(analysis.getId());
        vo.setSessionId(analysis.getSessionId());
        vo.setRunId(analysis.getRunId());
        vo.setMode(analysis.getMode());
        vo.setScore(analysis.getScore());
        vo.setScoreBand(analysis.getScoreBand());
        vo.setConclusion(analysis.getConclusion());
        vo.setDimensions(analysis.getDimensions());
        vo.setRequiredSkills(analysis.getRequiredSkills());
        vo.setCoveredSkills(analysis.getCoveredSkills());
        vo.setMissingSkills(analysis.getMissingSkills());
        vo.setWeakPointsHit(analysis.getWeakPointsHit());
        vo.setHardRequirements(analysis.getHardRequirements());
        vo.setRisks(analysis.getRisks());
        vo.setTodos(analysis.getTodos());
        vo.setAdvice(analysis.getAdvice());
        vo.setCostCredit(analysis.getCostCredit());
        vo.setCreatedAt(analysis.getCreatedAt());
        return vo;
    }

    private DecisionDtos.MessageVO toMessageVO(DecisionMessage message) {
        return message(message.getSeq(), message.getRole(), message.getContent(), message.getCreatedAt());
    }

    private DecisionDtos.MessageVO message(Integer seq, String role, String content,
                                           OffsetDateTime createdAt) {
        DecisionDtos.MessageVO vo = new DecisionDtos.MessageVO();
        vo.setSeq(seq);
        vo.setRole(role);
        vo.setContent(content);
        vo.setCreatedAt(createdAt);
        return vo;
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取上传文件失败", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "JD 抽取结果序列化失败", e);
        }
    }

    /**
     * 与 agent 侧 {@code decide._band} 完全对齐的档位兜底。
     *
     * <p>正式评估（DECIDE）不回传 scoreBand，只有预览回传；两边阈值若不一致，
     * 同一个分数在"预览"和"正式报告"里会显示成不同档位——所以阈值 75/60 必须写死成一份。
     */
    private static String band(Integer score) {
        if (score == null) {
            return null;
        }
        if (score >= 75) {
            return "高匹配";
        }
        if (score >= 60) {
            return "可争取";
        }
        return "差距明显";
    }

    private static String abbreviate(String text, int limit) {
        if (text == null || text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "...";
    }

    private static List<String> nullSafe(List<String> value) {
        return value == null ? List.of() : value;
    }

    private static String strOf(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private static Integer intOf(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Long longOf(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> stepListOf(Object value) {
        List<Map<String, Object>> steps = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    steps.add((Map<String, Object>) map);
                }
            }
        }
        return steps;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> hitListOf(Object value) {
        return stepListOf(value);
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
