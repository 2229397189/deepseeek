package com.lq.deepseek.service.impl;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
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
 * 用户画像 / 长期记忆行为测试。
 *
 * <p>钉死三条：标签必须来自真实产出（简历 / 决策 / 面试）并去重；
 * 记忆首次为空时由决策产出播种 PENDING；确认与修正只改状态不丢原文。
 */
class ProfileServiceImplTest {

    private static final Long USER_ID = 7L;

    private FileAssetMapper fileAssetMapper;
    private DecisionAnalysisMapper analysisMapper;
    private InterviewReportMapper reportMapper;
    private LongTermMemoryMapper memoryMapper;
    private CapabilityTagMapper tagMapper;
    private ProfileServiceImpl service;

    private final List<LongTermMemory> insertedMemories = new ArrayList<>();
    private final List<CapabilityTag> insertedTags = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fileAssetMapper = mock(FileAssetMapper.class);
        analysisMapper = mock(DecisionAnalysisMapper.class);
        reportMapper = mock(InterviewReportMapper.class);
        memoryMapper = mock(LongTermMemoryMapper.class);
        tagMapper = mock(CapabilityTagMapper.class);
        service = new ProfileServiceImpl(fileAssetMapper, analysisMapper, reportMapper, memoryMapper, tagMapper);

        when(tagMapper.insert(any(CapabilityTag.class))).thenAnswer(invocation -> {
            insertedTags.add(invocation.getArgument(0));
            return 1;
        });
        when(memoryMapper.insert(any(LongTermMemory.class))).thenAnswer(invocation -> {
            insertedMemories.add(invocation.getArgument(0));
            return 1;
        });
        when(memoryMapper.updateById(any(LongTermMemory.class))).thenReturn(1);
    }

    @Test
    void tags_aggregatesFromResumeDecisionAndInterview() {
        when(fileAssetMapper.selectList(any())).thenReturn(List.of(resumeAsset()));
        when(analysisMapper.selectList(any())).thenReturn(List.of(analysis()));
        when(reportMapper.selectList(any())).thenReturn(List.of(report()));

        List<ProfileDtos.CapabilityTag> tags = service.tags(USER_ID);

        assertThat(tags).anySatisfy(t -> {
            assertThat(t.getTag()).isEqualTo("Java");
            assertThat(t.getSource()).isEqualTo("RESUME");
            assertThat(t.getCategory()).isEqualTo("SKILL");
        });
        assertThat(tags).anySatisfy(t -> {
            assertThat(t.getTag()).isEqualTo("Kafka");
            assertThat(t.getCategory()).isEqualTo("GAP");
            assertThat(t.getSource()).isEqualTo("DECISION");
        });
        assertThat(tags).anySatisfy(t -> {
            assertThat(t.getTag()).isEqualTo("JVM");
            assertThat(t.getCategory()).isEqualTo("WEAK");
            assertThat(t.getSource()).isEqualTo("INTERVIEW");
        });
        // 落库保证画像与图谱同源
        assertThat(insertedTags).isNotEmpty();
    }

    @Test
    void tags_deduplicatesSameTagFromSameSource() {
        FileAsset first = resumeAsset();
        FileAsset second = resumeAsset();
        second.setId(5002L);
        when(fileAssetMapper.selectList(any())).thenReturn(List.of(first, second));
        when(analysisMapper.selectList(any())).thenReturn(List.of());
        when(reportMapper.selectList(any())).thenReturn(List.of());

        List<ProfileDtos.CapabilityTag> tags = service.tags(USER_ID);

        assertThat(tags.stream().filter(t -> "Java".equals(t.getTag())
                && "RESUME".equals(t.getSource())).count()).isEqualTo(1);
    }

    @Test
    void memories_seedsPendingFromDecisionsWhenEmpty() {
        when(memoryMapper.selectList(any())).thenReturn(List.of());
        when(analysisMapper.selectList(any())).thenReturn(List.of(analysis()));

        List<ProfileDtos.LongTermMemory> memories = service.memories(USER_ID);

        assertThat(memories).isNotEmpty();
        assertThat(memories).allSatisfy(m -> assertThat(m.getStatus()).isEqualTo("PENDING"));
        assertThat(insertedMemories).hasSameSizeAs(memories);
        assertThat(insertedMemories).anySatisfy(m -> assertThat(m.getContent()).contains("Kafka"));
    }

    @Test
    void memories_returnsExistingWithoutReseeding() {
        LongTermMemory existing = new LongTermMemory();
        existing.setId(1L);
        existing.setUserId(USER_ID);
        existing.setContent("既有记忆");
        existing.setStatus("CONFIRMED");
        when(memoryMapper.selectList(any())).thenReturn(List.of(existing));

        List<ProfileDtos.LongTermMemory> memories = service.memories(USER_ID);

        assertThat(memories).hasSize(1);
        assertThat(memories.get(0).getStatus()).isEqualTo("CONFIRMED");
        assertThat(insertedMemories).isEmpty();
    }

    @Test
    void confirmMemory_setsConfirmedStatus() {
        when(memoryMapper.selectById(anyLong())).thenReturn(pendingMemory());

        service.confirmMemory(USER_ID, 1L);

        ArgumentCaptor<LongTermMemory> captor = ArgumentCaptor.forClass(LongTermMemory.class);
        verify(memoryMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    void correctMemory_setsContentAndCorrectedStatus() {
        when(memoryMapper.selectById(anyLong())).thenReturn(pendingMemory());

        service.correctMemory(USER_ID, 1L, "修正后的内容");

        ArgumentCaptor<LongTermMemory> captor = ArgumentCaptor.forClass(LongTermMemory.class);
        verify(memoryMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("CORRECTED");
        assertThat(captor.getValue().getContent()).isEqualTo("修正后的内容");
    }

    @Test
    void correctMemory_blankContent_isRejected() {
        when(memoryMapper.selectById(anyLong())).thenReturn(pendingMemory());

        assertThatThrownBy(() -> service.correctMemory(USER_ID, 1L, "  "))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void confirmMemory_otherUserMemory_isReportedAsNotFound() {
        LongTermMemory other = pendingMemory();
        other.setUserId(99L);
        when(memoryMapper.selectById(anyLong())).thenReturn(other);

        assertThatThrownBy(() -> service.confirmMemory(USER_ID, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ------------------------------------------------------------------

    private static FileAsset resumeAsset() {
        FileAsset asset = new FileAsset();
        asset.setId(5001L);
        asset.setUserId(USER_ID);
        asset.setBizType("RESUME");
        asset.setFileName("resume.txt");
        asset.setParseStatus("SUCCESS");
        asset.setParseResult(Map.of("skills", List.of("Java", "Redis")));
        return asset;
    }

    private static DecisionAnalysis analysis() {
        DecisionAnalysis analysis = new DecisionAnalysis();
        analysis.setId(1L);
        analysis.setUserId(USER_ID);
        analysis.setCoveredSkills(List.of("Java"));
        analysis.setMissingSkills(List.of("Kafka"));
        analysis.setRisks(List.of("缺少 Kafka 实战"));
        analysis.setAdvice("建议补齐 Kafka 后投递。");
        return analysis;
    }

    private static InterviewReport report() {
        InterviewReport report = new InterviewReport();
        report.setId(1L);
        report.setUserId(USER_ID);
        report.setWeakPoints(List.of("JVM"));
        return report;
    }

    private static LongTermMemory pendingMemory() {
        LongTermMemory memory = new LongTermMemory();
        memory.setId(1L);
        memory.setUserId(USER_ID);
        memory.setContent("待确认记忆");
        memory.setStatus("PENDING");
        return memory;
    }
}
