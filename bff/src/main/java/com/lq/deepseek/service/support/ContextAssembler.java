package com.lq.deepseek.service.support;

import com.lq.deepseek.domain.entity.AgentContextSlice;
import com.lq.deepseek.domain.entity.AgentContextSnapshot;
import com.lq.deepseek.domain.mapper.AgentContextSliceMapper;
import com.lq.deepseek.domain.mapper.AgentContextSnapshotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent Context 组装器：把一次 AI 调用的全部上下文素材打包成可追踪的快照。
 *
 * <p>三条规则：
 * <ol>
 *   <li><b>证据分层</b>：HIGH（用户真实输入）> MEDIUM（历史结论）> LOW（检索召回），
 *       同型素材多份时按等级与新旧排序；</li>
 *   <li><b>token 预算裁剪</b>：超出预算先裁 LOW、再裁 MEDIUM，被裁切片数量即降级依据；
 *       裁剪后仍超预算的保留标记，不静默截断内容；</li>
 *   <li><b>degraded 可见</b>：语料缺失 / 触发裁剪都写入 degraded 与原因，
 *       让"这次回答质量差"可以归因到上下文质量，而不是只看模型输出。</li>
 * </ol>
 * 快照与切片全部落库（agent_context_snapshots / agent_context_slices，V2 建表），
 * 会话与审计通过 snapshot_id 串起"哪一次调用用了哪些证据"。
 */
@Component
@RequiredArgsConstructor
public class ContextAssembler {

    /** 中英混排的粗粒度 token 估算：约 2 字符 1 token，与 agent 侧 context_snapshot 估算保持同量级 */
    static final int CHARS_PER_TOKEN = 2;

    private final AgentContextSnapshotMapper snapshotMapper;
    private final AgentContextSliceMapper sliceMapper;

    /** 待打包的一个上下文素材。 */
    public record Material(String sliceType, EvidenceLevel evidenceLevel, String content,
                           Map<String, Object> metadata) {
    }

    public enum EvidenceLevel {
        HIGH, MEDIUM, LOW;

        static int rankOf(EvidenceLevel level) {
            return switch (level) {
                case HIGH -> 0;
                case MEDIUM -> 1;
                case LOW -> 2;
            };
        }
    }

    /** 组装结果：落库后的快照（含 snapshot / token_used / degraded）。 */
    public AgentContextSnapshot assemble(Long userId, Long sessionId, String bizType,
                                         List<Material> materials, Integer tokenBudget) {
        int budget = tokenBudget != null && tokenBudget > 0 ? tokenBudget : 8000;

        List<Material> ordered = materials.stream()
                .filter(m -> m != null && StringUtils.hasText(m.content()))
                .sorted(Comparator.comparingInt((Material m) -> EvidenceLevel.rankOf(m.evidenceLevel())))
                .toList();

        List<AgentContextSlice> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        int used = 0;
        for (Material m : ordered) {
            int tokens = estimateTokens(m.content());
            if (used + tokens > budget && !kept.isEmpty()) {
                dropped.add(m.sliceType() + ":" + estimateTokens(m.content()));
                continue;
            }
            AgentContextSlice slice = new AgentContextSlice();
            slice.setSliceType(m.sliceType());
            slice.setEvidenceLevel(m.evidenceLevel().name());
            slice.setContent(m.content());
            slice.setTokenCount(tokens);
            slice.setMetadata(m.metadata());
            kept.add(slice);
            used += tokens;
        }

        boolean degraded = !dropped.isEmpty() || kept.size() < ordered.size();
        String degradedReason = degraded
                ? "上下文超出预算，裁剪切片：" + String.join(",", dropped) + (kept.isEmpty() ? "（预算内无可用素材）" : "")
                : null;

        Map<String, Object> snapshotPayload = new LinkedHashMap<>();
        snapshotPayload.put("bizType", bizType);
        snapshotPayload.put("tokenBudget", budget);
        snapshotPayload.put("slices", kept.stream().map(s -> Map.of(
                "sliceType", s.getSliceType(),
                "evidenceLevel", s.getEvidenceLevel(),
                "tokenCount", s.getTokenCount(),
                "content", s.getContent())).toList());

        AgentContextSnapshot snapshot = new AgentContextSnapshot();
        snapshot.setUserId(userId);
        snapshot.setSessionId(sessionId);
        snapshot.setBizType(bizType);
        snapshot.setTokenBudget(budget);
        snapshot.setTokenUsed(used);
        snapshot.setDegraded(degraded);
        snapshot.setDegradedReason(degradedReason);
        snapshot.setSnapshot(snapshotPayload);
        snapshotMapper.insert(snapshot);

        for (AgentContextSlice slice : kept) {
            slice.setSnapshotId(snapshot.getId());
            sliceMapper.insert(slice);
        }
        return snapshot;
    }

    static int estimateTokens(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        return Math.max(1, (content.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN);
    }
}
