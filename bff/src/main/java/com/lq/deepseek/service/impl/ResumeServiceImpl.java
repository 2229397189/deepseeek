package com.lq.deepseek.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lq.deepseek.common.BusinessException;
import com.lq.deepseek.common.ErrorCode;
import com.lq.deepseek.config.props.LqProperties;
import com.lq.deepseek.domain.entity.FileAsset;
import com.lq.deepseek.domain.mapper.FileAssetMapper;
import com.lq.deepseek.dto.ResumeDtos;
import com.lq.deepseek.gateway.AiInvocationGateway;
import com.lq.deepseek.gateway.model.AgentInvokeCommand;
import com.lq.deepseek.gateway.model.AgentInvokeResult;
import com.lq.deepseek.service.ResumeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 简历中心实现。
 *
 * <p>状态机：PENDING -> PARSING -> SUCCESS / FAILED。任何一次解析失败都必须落到 FAILED 且带
 * error_msg（库里 ck_file_assets_status_deleted 约束强制），前端据此给出"重试"入口——
 * 静默失败会让用户以为简历"没内容"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeServiceImpl implements ResumeService {

    static final String BIZ_ASSET = "RESUME";
    static final String BIZ_PARSE = "RESUME_PARSE";
    static final String BIZ_QUESTION = "RESUME_QUESTION";
    static final String SPEC_PARSE = "resume-parse-v1";
    static final String SPEC_POLISH = "resume-polish-v1";

    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_PARSING = "PARSING";
    static final String STATUS_SUCCESS = "SUCCESS";
    static final String STATUS_FAILED = "FAILED";

    /** 与 DDL 的 ck_file_assets_biz / lq.storage.allowed-extensions 保持一致的子集 */
    static final Set<String> ALLOWED_EXTS = Set.of("pdf", "docx", "md", "txt");

    static final long PARSE_TIMEOUT_MS = 120_000L;
    static final long PARSE_FREEZE_CREDIT = 10L;
    static final long POLISH_FREEZE_CREDIT = 5L;

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private final FileAssetMapper fileAssetMapper;
    private final AiInvocationGateway gateway;
    private final LqProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public ResumeDtos.ResumeVO uploadResume(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_EMPTY);
        }
        String fileName = StringUtils.cleanPath(
                Objects.requireNonNullElse(file.getOriginalFilename(), "resume.txt"));
        String ext = extensionOf(fileName);
        if (!ALLOWED_EXTS.contains(ext)) {
            throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED,
                    "简历仅支持 pdf / docx / md / txt，当前为 ." + (ext.isEmpty() ? "未知" : ext));
        }
        long maxSize = properties.getStorage().getMaxFileSize();
        if (file.getSize() > maxSize) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE,
                    "文件超过 " + (maxSize / 1024 / 1024) + "MB 上限");
        }

        byte[] bytes = readBytes(file);
        String sha256 = sha256(bytes);

        FileAsset existing = fileAssetMapper.selectActiveBySha(userId, sha256, BIZ_ASSET);
        if (existing != null && STATUS_SUCCESS.equals(existing.getParseStatus())) {
            log.info("简历命中既有资产，跳过解析 userId={} assetId={}", userId, existing.getId());
            return toVO(existing, true);
        }
        if (existing != null && STATUS_PARSING.equals(existing.getParseStatus())) {
            // 解析进行中：回放当前状态，等待前端轮询，不重复发起 AI 调用
            return toVO(existing, true);
        }

        FileAsset asset = existing;
        if (asset == null) {
            asset = new FileAsset();
            asset.setUserId(userId);
            asset.setBizType(BIZ_ASSET);
            asset.setFileName(fileName);
            asset.setContentType(file.getContentType());
            asset.setSizeBytes((long) bytes.length);
            asset.setSha256(sha256);
            asset.setParseStatus(STATUS_PENDING);
            asset.setObjectKey(store(userId, sha256, ext, bytes));
            fileAssetMapper.insert(asset);
            log.info("简历资产已入库 userId={} assetId={} name={}", userId, asset.getId(), fileName);
        }
        // 失败重传：复用同一条资产，避免资产表被"同一份简历的多次重试"刷屏
        return parse(userId, asset, bytes, fileName);
    }

    @Override
    public ResumeDtos.ResumeVO getResume(Long userId, Long assetId) {
        return toVO(requireOwned(userId, assetId), false);
    }

    @Override
    public List<ResumeDtos.AssetBrief> listResumes(Long userId) {
        List<FileAsset> rows = fileAssetMapper.selectList(new LambdaQueryWrapper<FileAsset>()
                .eq(FileAsset::getUserId, userId)
                .eq(FileAsset::getBizType, BIZ_ASSET)
                .orderByDesc(FileAsset::getCreatedAt)
                .last("LIMIT 50"));
        List<ResumeDtos.AssetBrief> briefs = new ArrayList<>(rows.size());
        for (FileAsset row : rows) {
            ResumeDtos.AssetBrief brief = new ResumeDtos.AssetBrief();
            brief.setAssetId(row.getId());
            brief.setFileName(row.getFileName());
            brief.setParseStatus(row.getParseStatus());
            brief.setUpdatedAt(row.getUpdatedAt());
            Map<String, Object> profile = row.getParseResult();
            if (profile != null) {
                brief.setCompleteness(intOf(profile.get("completeness")));
                brief.setExperienceYears(intOf(profile.get("experienceYears")));
                Object skills = profile.get("skills");
                brief.setSkillCount(skills instanceof List<?> list ? list.size() : 0);
            }
            briefs.add(brief);
        }
        return briefs;
    }

    @Override
    public ResumeDtos.PolishVO polish(Long userId, ResumeDtos.PolishRequest request) {
        if (request == null || !StringUtils.hasText(request.getSelectedText())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请先选中需要处理的内容");
        }
        if (request.getAssetId() != null) {
            requireOwned(userId, request.getAssetId());
        }

        String mode = StringUtils.hasText(request.getMode()) ? request.getMode().toUpperCase() : "POLISH";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", mode);
        payload.put("selectedText", request.getSelectedText());
        payload.put("instruction", request.getInstruction());
        payload.put("jobTitle", request.getJobTitle());
        payload.put("jdText", request.getJdText());

        AgentInvokeResult result = gateway.invoke(AgentInvokeCommand.builder()
                .bizType(BIZ_QUESTION)
                .bizId(request.getAssetId())
                .userId(userId)
                .stage(mode)
                .specHash(SPEC_POLISH)
                .payload(payload)
                .expectedCredit(POLISH_FREEZE_CREDIT)
                .build());
        if (!result.succeeded()) {
            throw new BusinessException(ErrorCode.AGENT_STAGE_FAILED,
                    Objects.requireNonNullElse(result.getErrorMsg(), "AI 润色失败，请稍后重试"));
        }

        Map<String, Object> output = result.getOutput();
        ResumeDtos.PolishVO vo = new ResumeDtos.PolishVO();
        vo.setRunId(result.getRunId());
        vo.setMode(strOf(output.get("mode"), mode));
        vo.setTarget(strOf(output.get("target"), null));
        vo.setOriginal(strOf(output.get("original"), request.getSelectedText()));
        vo.setPolished(strOf(output.get("polished"), null));
        vo.setReasons(strListOf(output.get("reasons")));
        vo.setMatchedSkills(strListOf(output.get("matchedSkills")));
        vo.setCostCredit(result.getCostCredit());
        vo.setFlightMode(result.getFlightMode());
        return vo;
    }

    @Override
    public ResumeDtos.SaveBodyResult saveBody(Long userId, Long assetId, String body) {
        FileAsset asset = requireOwned(userId, assetId);
        // Store body in parseResult map under 'body' key
        Map<String, Object> profile = asset.getParseResult();
        if (profile == null) {
            profile = new LinkedHashMap<>();
        } else {
            profile = new LinkedHashMap<>(profile);
        }
        profile.put("body", body);
        fileAssetMapper.updateParseState(asset.getId(), asset.getParseStatus(), toJson(profile), null);

        ResumeDtos.SaveBodyResult result = new ResumeDtos.SaveBodyResult();
        result.setAssetId(asset.getId());
        result.setMessage("正文已保存");
        return result;
    }

    // -----------------------------------------------------------------------
    // 内部实现
    // -----------------------------------------------------------------------

    private ResumeDtos.ResumeVO parse(Long userId, FileAsset asset, byte[] bytes, String fileName) {
        fileAssetMapper.updateParseState(asset.getId(), STATUS_PARSING, null, null);
        asset.setParseStatus(STATUS_PARSING);
        asset.setErrorMsg(null);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fileName", fileName);
        payload.put("fileBase64", Base64.getEncoder().encodeToString(bytes));
        payload.put("sizeBytes", bytes.length);

        AgentInvokeResult result;
        try {
            result = gateway.invoke(AgentInvokeCommand.builder()
                    .bizType(BIZ_PARSE)
                    .bizId(asset.getId())
                    .userId(userId)
                    .stage("PARSE")
                    .specHash(SPEC_PARSE)
                    .payload(payload)
                    .timeoutMs(PARSE_TIMEOUT_MS)
                    .expectedCredit(PARSE_FREEZE_CREDIT)
                    .build());
        } catch (BusinessException e) {
            // 额度不足属于"用户可自行解决"的失败，必须原样上抛，不能被降级成"解析失败"
            boolean fatal = e.getErrorCode() == ErrorCode.INSUFFICIENT_CREDIT;
            markFailed(asset, e.getMessage());
            if (fatal) {
                throw e;
            }
            log.warn("简历解析失败 assetId={} reason={}", asset.getId(), e.getMessage());
            return toVO(asset, false);
        }

        if (!result.succeeded()) {
            markFailed(asset, Objects.requireNonNullElse(result.getErrorMsg(), "简历解析失败"));
            return toVO(asset, false);
        }

        fileAssetMapper.updateParseState(asset.getId(), STATUS_SUCCESS, toJson(result.getOutput()), null);
        asset.setParseStatus(STATUS_SUCCESS);
        asset.setParseResult(result.getOutput());
        return toVO(asset, false);
    }

    private void markFailed(FileAsset asset, String message) {
        String reason = StringUtils.hasText(message) ? message : "简历解析失败";
        fileAssetMapper.updateParseState(asset.getId(), STATUS_FAILED, null, reason);
        asset.setParseStatus(STATUS_FAILED);
        asset.setErrorMsg(reason);
    }

    private FileAsset requireOwned(Long userId, Long assetId) {
        if (assetId == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "缺少资产 ID");
        }
        FileAsset asset = fileAssetMapper.selectById(assetId);
        // 他人资产一律按"不存在"处理，避免通过错误码探测资源是否存在
        if (asset == null || !Objects.equals(asset.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        return asset;
    }

    private String store(Long userId, String sha256, String ext, byte[] bytes) {
        Path root = Paths.get(properties.getStorage().getLocalRoot()).toAbsolutePath().normalize();
        String relative = userId + "/" + LocalDate.now().format(MONTH) + "/" + sha256 + "." + ext;
        Path target = root.resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件落盘失败", e);
        }
        return relative;
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取上传文件失败", e);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "计算文件指纹失败", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "解析结果序列化失败", e);
        }
    }

    private ResumeDtos.ResumeVO toVO(FileAsset asset, boolean deduplicated) {
        ResumeDtos.ResumeVO vo = new ResumeDtos.ResumeVO();
        vo.setAssetId(asset.getId());
        vo.setFileName(asset.getFileName());
        vo.setSizeBytes(asset.getSizeBytes());
        vo.setSha256(asset.getSha256());
        vo.setParseStatus(asset.getParseStatus());
        vo.setErrorMsg(asset.getErrorMsg());
        vo.setProfile(asset.getParseResult());
        vo.setCreatedAt(asset.getCreatedAt());
        vo.setDeduplicated(deduplicated);
        // Extract Markdown body from parseResult if available
        Map<String, Object> profile = asset.getParseResult();
        if (profile != null && profile.get("body") instanceof String bodyStr) {
            vo.setBody(bodyStr);
        }
        return vo;
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }

    private static Integer intOf(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static String strOf(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
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
}
