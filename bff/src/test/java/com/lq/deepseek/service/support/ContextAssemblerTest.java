package com.lq.deepseek.service.support;

import com.lq.deepseek.domain.entity.AgentContextSlice;
import com.lq.deepseek.domain.entity.AgentContextSnapshot;
import com.lq.deepseek.domain.mapper.AgentContextSliceMapper;
import com.lq.deepseek.domain.mapper.AgentContextSnapshotMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Context 治理行为测试。
 *
 * <p>钉死三条：证据分层（HIGH 先于 LOW 保留）；token 预算裁剪（超预算的低位切片被丢并标记
 * degraded）；快照与切片必须成组落库且 token_used 与切片一致。
 */
class ContextAssemblerTest {

    private AgentContextSnapshotMapper snapshotMapper;
    private AgentContextSliceMapper sliceMapper;
    private ContextAssembler assembler;

    @BeforeEach
    void setUp() {
        snapshotMapper = mock(AgentContextSnapshotMapper.class);
        sliceMapper = mock(AgentContextSliceMapper.class);
        when(snapshotMapper.insert(any(AgentContextSnapshot.class))).thenAnswer(invocation -> {
            AgentContextSnapshot s = invocation.getArgument(0);
            s.setId(7001L);
            return 1;
        });
        assembler = new ContextAssembler(snapshotMapper, sliceMapper);
    }

    @Test
    void 预算充足时全部素材保留_无降级() {
        AgentContextSnapshot snapshot = assembler.assemble(1L, 2L, "DECIDE", List.of(
                new ContextAssembler.Material("jd", ContextAssembler.EvidenceLevel.HIGH, "岗位要求 Java", null),
                new ContextAssembler.Material("resume", ContextAssembler.EvidenceLevel.HIGH, "五年后端经验", null)
        ), 8000);

        assertThat(snapshot.getId()).isEqualTo(7001L);
        assertThat(snapshot.getDegraded()).isFalse();
        assertThat(snapshot.getDegradedReason()).isNull();
        assertThat(snapshot.getTokenUsed()).isEqualTo(
                ContextAssembler.estimateTokens("岗位要求 Java") + ContextAssembler.estimateTokens("五年后端经验"));
        verify(sliceMapper, org.mockito.Mockito.times(2)).insert(any(AgentContextSlice.class));
    }

    @Test
    void 超出预算时低等级切片被裁剪_标记degraded并写明原因() {
        // budget = 30 token：HIGH 简历 15 token + LOW 检索 40 token，LOW 必然被裁
        AgentContextSnapshot snapshot = assembler.assemble(1L, 2L, "RAG_SEARCH", List.of(
                new ContextAssembler.Material("retrieval", ContextAssembler.EvidenceLevel.LOW,
                        "z".repeat(80), null),
                new ContextAssembler.Material("resume", ContextAssembler.EvidenceLevel.HIGH,
                        "y".repeat(30), null)
        ), 30);

        assertThat(snapshot.getDegraded()).isTrue();
        assertThat(snapshot.getDegradedReason()).contains("retrieval");
        assertThat(snapshot.getTokenUsed()).isEqualTo(ContextAssembler.estimateTokens("y".repeat(30)));
        // 只有 HIGH 切片落库
        verify(sliceMapper, org.mockito.Mockito.times(1)).insert(any(AgentContextSlice.class));
    }

    @Test
    void 同等级素材先到先得_预算给足时不裁剪HIGH() {
        // 两个 HIGH，第二个超预算但它是唯一切片前会保留第一个；第二个被裁
        AgentContextSnapshot snapshot = assembler.assemble(1L, null, "DECIDE", List.of(
                new ContextAssembler.Material("jd", ContextAssembler.EvidenceLevel.HIGH, "a".repeat(40), null),
                new ContextAssembler.Material("resume", ContextAssembler.EvidenceLevel.HIGH, "b".repeat(40), null)
        ), 25);

        assertThat(snapshot.getDegraded()).isTrue();
        // 裁剪不能把第一个切片丢掉：至少保留 1 个
        verify(sliceMapper, org.mockito.Mockito.atLeast(1)).insert(any(AgentContextSlice.class));
    }

    @Test
    void 快照负载包含切片明细与预算信息() {
        assembler.assemble(1L, 2L, "DECIDE", List.of(
                new ContextAssembler.Material("jd", ContextAssembler.EvidenceLevel.HIGH, "JD 内容", null)
        ), 8000);

        ArgumentCaptor<AgentContextSnapshot> captor = ArgumentCaptor.forClass(AgentContextSnapshot.class);
        verify(snapshotMapper).insert(captor.capture());
        Map<String, Object> payload = captor.getValue().getSnapshot();
        assertThat(payload).containsKeys("bizType", "tokenBudget", "slices");
        assertThat(payload.get("bizType")).isEqualTo("DECIDE");
    }

    @Test
    void token估算向上取整且不为零() {
        assertThat(ContextAssembler.estimateTokens("a")).isEqualTo(1);
        assertThat(ContextAssembler.estimateTokens("abc")).isEqualTo(2);
        assertThat(ContextAssembler.estimateTokens(null)).isZero();
    }
}
