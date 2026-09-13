package com.lq.deepseek.service.support;

import com.lq.deepseek.config.props.LqProperties;
import com.lq.deepseek.domain.mapper.KbChunkMapper;
import com.lq.deepseek.dto.KbDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索融合器（RRF：Reciprocal Rank Fusion）。
 *
 * <p>多路召回各自排序，融合分 = Σ w_i / (rrfK + rank_i)，K 取 60 抑制头部排名的过度影响：
 * <ul>
 *   <li><b>FTS 路</b>：PostgreSQL 全文检索，tsv @@ tsquery 的 ts_rank（中文按 simple 分词）；</li>
 *   <li><b>trigram 路</b>：pg_trgm 相似度，兜住 FTS 分不出词的模糊匹配；</li>
 *   <li><b>向量路</b>：pgvector KNN。embedding 列与向量扩展就绪且存在查询向量时启用，
 *       否则该路不参与融合（调用方据此标记 degraded）。</li>
 * </ul>
 * 两两路的召回候选取并集后再融合，兼顾"精确命中"与"语义模糊命中"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HybridRetriever {

    private static final int CANDIDATE_LIMIT = 200;

    private final KbChunkMapper chunkMapper;
    private final LqProperties properties;

    /**
     * @param queryVector 查询向量；null 表示向量路不可用（无 embedding 提供方或 pgvector 未启用）
     * @return 融合后的命中列表，score 为 RRF 分（0-1 区间，已按最大分归一化）
     */
    public List<KbDtos.KbHit> search(Long userId, String query, int topK, double[] queryVector) {
        LqProperties.Kb.Retrieval profile = properties.getKb().getRetrieval();
        List<Map<String, Object>> candidates =
                chunkMapper.searchHybrid(userId, query, profile.getMinTrgm(), CANDIDATE_LIMIT);
        if (candidates.isEmpty()) {
            return List.of();
        }

        // 各路独立排序：rank 从 1 开始，未上榜的候选该路不贡献分数
        List<Map<String, Object>> ftsRanked = new ArrayList<>(candidates);
        ftsRanked.sort(byDesc("ftsRank"));
        List<Map<String, Object>> trgmRanked = new ArrayList<>(candidates);
        trgmRanked.sort(byDesc("trgmSim"));

        boolean vectorEnabled = queryVector != null && queryVector.length > 0 && chunkMapper.pgvectorAvailable();
        List<Map<String, Object>> vectorRanked = vectorEnabled
                ? candidates.stream().sorted(byDesc("vectorDistance")).toList()
                : List.of();

        // RRF 融合
        Map<Long, Double> fused = new LinkedHashMap<>();
        Map<Long, Map<String, Object>> byChunk = new HashMap<>();
        accumulate(fused, byChunk, ftsRanked, profile.getWFts());
        accumulate(fused, byChunk, trgmRanked, profile.getWTrgm());
        if (vectorEnabled) {
            accumulate(fused, byChunk, vectorRanked, profile.getWVector());
        }

        // 归一化到 0-1，排序输出
        double max = fused.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        List<Map.Entry<Long, Double>> ordered = fused.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(topK)
                .toList();

        List<KbDtos.KbHit> hits = new ArrayList<>();
        for (Map.Entry<Long, Double> entry : ordered) {
            Map<String, Object> row = byChunk.get(entry.getKey());
            KbDtos.KbHit hit = new KbDtos.KbHit();
            hit.setDocumentId(asLong(row.get("documentId")));
            hit.setContent(str(row.get("content"), ""));
            hit.setScore(round(entry.getValue() / max));
            // 前端展示的两路分数：ftsScore = FTS 秩分，vectorScore = 语义相似度（trigram 近似）
            hit.setFtsScore(round(asDouble(row.get("ftsRank"))));
            hit.setVectorScore(round(asDouble(row.get("trgmSim"))));
            hits.add(hit);
        }
        if (!vectorEnabled) {
            log.debug("向量路未参与融合（pgvector 或查询向量未就绪），混合检索以 FTS+trigram 双路运行");
        }
        return hits;
    }

    private void accumulate(Map<Long, Double> fused, Map<Long, Map<String, Object>> byChunk,
                            List<Map<String, Object>> ranked, double weight) {
        int rank = 1;
        for (Map<String, Object> row : ranked) {
            long chunkId = asLong(row.get("chunkId"));
            fused.merge(chunkId, weight / (properties.getKb().getRetrieval().getRrfK() + rank), Double::sum);
            byChunk.putIfAbsent(chunkId, row);
            rank++;
        }
    }

    private static Comparator<Map<String, Object>> byDesc(String key) {
        return (a, b) -> Double.compare(asDouble(b.get(key)), asDouble(a.get(key)));
    }

    private static double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static Long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private static String str(Object value, String fallback) {
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
