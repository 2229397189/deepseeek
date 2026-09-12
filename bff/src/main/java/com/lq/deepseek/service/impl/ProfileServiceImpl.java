package com.lq.deepseek.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lq.deepseek.common.BusinessException;
import com.lq.deepseek.common.ErrorCode;
import com.lq.deepseek.domain.entity.CapabilityTag;
import com.lq.deepseek.domain.entity.DecisionAnalysis;
import com.lq.deepseek.domain.entity.FileAsset;
import com.lq.deepseek.domain.entity.InterviewReport;
import com.lq.deepseek.domain.entity.LongTermMemory;
import com.lq.deepseek.domain.mapper.CapabilityTagMapper;
import com.lq.deepseek.domain.mapper.DecisionAnalysisMapper;
import com.lq.deepseek.domain.mapper.FileAssetMapper;
import com.lq.deepseek.domain.mapper.InterviewReportMapper;
import com.lq.deepseek.domain.mapper.LongTermMemoryMapper;
import com.lq.deepseek.dto.ProfileDtos;
import com.lq.deepseek.service.ProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 用户画像 / 长期记忆实现。
 *
 * <p>能力标签从三类真实产出聚合（简历技能 + 决策分析 + 面试报告），落 {@code capability_tag}
 * 以保证图谱与画像同源；长期记忆在首次为空时由决策产出播种 PENDING，等待用户确认 / 修正。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileServiceImpl implements ProfileService {

    private static final String BIZ_RESUME = "RESUME";
    private static final String STATUS_SUCCESS = "SUCCESS";

    private final FileAssetMapper fileAssetMapper;
    private final DecisionAnalysisMapper analysisMapper;
    private final InterviewReportMapper reportMapper;
    private final LongTermMemoryMapper memoryMapper;
    private final CapabilityTagMapper tagMapper;

    @Override
    public List<ProfileDtos.CapabilityTag> tags(Long userId) {
        List<CapabilityTag> tags = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1) 简历技能
        List<FileAsset> resumes = fileAssetMapper.selectList(new LambdaQueryWrapper<FileAsset>()
                .eq(FileAsset::getUserId, userId)
                .eq(FileAsset::getBizType, BIZ_RESUME)
                .eq(FileAsset::getParseStatus, STATUS_SUCCESS)
                .last("LIMIT 50"));
        for (FileAsset asset : resumes) {
            Map<String, Object> profile = asset.getParseResult();
            List<String> skills = profile == null ? List.of() : strListOf(profile.get("skills"));
            for (String skill : skills) {
                addTag(tags, seen, skill, "SKILL", null, "RESUME", 0.9);
            }
        }

        // 2) 决策分析：覆盖项=优势，缺口项=短板
        List<DecisionAnalysis> analyses = analysisMapper.selectList(new LambdaQueryWrapper<DecisionAnalysis>()
                .eq(DecisionAnalysis::getUserId, userId)
                .last("LIMIT 50"));
        for (DecisionAnalysis analysis : analyses) {
            for (String skill : nullSafe(analysis.getCoveredSkills())) {
                addTag(tags, seen, skill, "STRENGTH", null, "DECISION", 0.8);
            }
            for (String skill : nullSafe(analysis.getMissingSkills())) {
                addTag(tags, seen, skill, "GAP", null, "DECISION", 0.8);
            }
        }

        // 3) 面试报告：薄弱点
        List<InterviewReport> reports = reportMapper.selectList(new LambdaQueryWrapper<InterviewReport>()
                .eq(InterviewReport::getUserId, userId)
                .last("LIMIT 50"));
        for (InterviewReport report : reports) {
            for (String weak : nullSafe(report.getWeakPoints())) {
                addTag(tags, seen, weak, "WEAK", null, "INTERVIEW", 0.85);
            }
        }

        // 落库保证画像与图谱同源（清+写幂等）
        tagMapper.delete(new LambdaQueryWrapper<CapabilityTag>().eq(CapabilityTag::getUserId, userId));
        for (CapabilityTag tag : tags) {
            tag.setId(null);
            tag.setUserId(userId);
            tagMapper.insert(tag);
        }
        return tags.stream().map(this::toTagVO).toList();
    }

    @Override
    public List<ProfileDtos.LongTermMemory> memories(Long userId) {
        List<LongTermMemory> memories = memoryMapper.selectList(new LambdaQueryWrapper<LongTermMemory>()
                .eq(LongTermMemory::getUserId, userId)
                .orderByDesc(LongTermMemory::getCreatedAt)
                .last("LIMIT 100"));
        if (memories.isEmpty()) {
            memories = seedFromDecisions(userId);
        }
        return memories.stream().map(this::toVO).toList();
    }

    @Override
    public void confirmMemory(Long userId, Long memoryId) {
        LongTermMemory memory = requireOwned(userId, memoryId);
        memory.setStatus("CONFIRMED");
        memoryMapper.updateById(memory);
    }

    @Override
    public void correctMemory(Long userId, Long memoryId, String correctedContent) {
        if (!StringUtils.hasText(correctedContent)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "修正内容不能为空");
        }
        LongTermMemory memory = requireOwned(userId, memoryId);
        memory.setContent(correctedContent);
        memory.setStatus("CORRECTED");
        memoryMapper.updateById(memory);
    }

    @Override
    public void deleteMemory(Long userId, Long memoryId) {
        requireOwned(userId, memoryId);
        // long_term_memory 无 deleted 列，MyBatis-Plus 走物理删除
        memoryMapper.deleteById(memoryId);
        log.info("长期记忆已删除 userId={} memoryId={}", userId, memoryId);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 由决策分析播种 PENDING 记忆：缺口 / 建议 / 风险各成一条。 */
    private List<LongTermMemory> seedFromDecisions(Long userId) {
        List<DecisionAnalysis> analyses = analysisMapper.selectList(new LambdaQueryWrapper<DecisionAnalysis>()
                .eq(DecisionAnalysis::getUserId, userId)
                .last("LIMIT 50"));
        List<LongTermMemory> seeded = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (DecisionAnalysis analysis : analyses) {
            for (String content : buildMemoryContents(analysis)) {
                if (seen.add(content)) {
                    LongTermMemory memory = new LongTermMemory();
                    memory.setUserId(userId);
                    memory.setContent(content);
                    memory.setStatus("PENDING");
                    memoryMapper.insert(memory);
                    seeded.add(memory);
                }
            }
        }
        log.info("由决策产出播种长期记忆 userId={} count={}", userId, seeded.size());
        return seeded;
    }

    private List<String> buildMemoryContents(DecisionAnalysis analysis) {
        List<String> contents = new ArrayList<>();
        if (!nullSafe(analysis.getMissingSkills()).isEmpty()) {
            contents.add("建议补强：" + String.join("、", nullSafe(analysis.getMissingSkills())));
        }
        if (StringUtils.hasText(analysis.getAdvice())) {
            contents.add(analysis.getAdvice());
        }
        if (!nullSafe(analysis.getRisks()).isEmpty()) {
            contents.add("风险提示：" + String.join("、", nullSafe(analysis.getRisks())));
        }
        return contents;
    }

    private LongTermMemory requireOwned(Long userId, Long memoryId) {
        if (memoryId == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "缺少记忆 ID");
        }
        LongTermMemory memory = memoryMapper.selectById(memoryId);
        if (memory == null || !Objects.equals(memory.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "记忆不存在");
        }
        return memory;
    }

    private void addTag(List<CapabilityTag> tags, Set<String> seen, String tag, String category,
                       String level, String source, double confidence) {
        if (!StringUtils.hasText(tag)) {
            return;
        }
        String key = tag.trim() + "|" + source;
        if (!seen.add(key)) {
            return;
        }
        CapabilityTag ct = new CapabilityTag();
        ct.setTag(tag.trim());
        ct.setCategory(category);
        ct.setLevel(level);
        ct.setSource(source);
        ct.setConfidence(confidence);
        tags.add(ct);
    }

    private ProfileDtos.CapabilityTag toTagVO(CapabilityTag tag) {
        ProfileDtos.CapabilityTag vo = new ProfileDtos.CapabilityTag();
        vo.setTag(tag.getTag());
        vo.setCategory(tag.getCategory());
        vo.setLevel(tag.getLevel());
        vo.setSource(tag.getSource());
        vo.setConfidence(tag.getConfidence());
        return vo;
    }

    private ProfileDtos.LongTermMemory toVO(LongTermMemory memory) {
        ProfileDtos.LongTermMemory vo = new ProfileDtos.LongTermMemory();
        vo.setId(memory.getId());
        vo.setContent(memory.getContent());
        vo.setStatus(memory.getStatus());
        vo.setCreatedAt(memory.getCreatedAt());
        return vo;
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

    private static List<String> nullSafe(List<String> value) {
        return value == null ? List.of() : value;
    }
}
