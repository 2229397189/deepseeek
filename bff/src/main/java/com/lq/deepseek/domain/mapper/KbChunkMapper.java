package com.lq.deepseek.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lq.deepseek.domain.entity.KbChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** 知识库分片 Mapper（表 kb_chunks 由 V2 创建）。 */
@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {

    /**
     * 混合检索候选：PostgreSQL FTS（tsv @@ tsquery 的 ts_rank）+ pg_trgm 相似度双路打分。
     *
     * <p>任一路命中的切片都进入候选集（OR 语义），最终排序与 RRF 融合在 Java 侧完成，
     * 便于按 RetrievalProfile 调权重；命中规模由 LIMIT 兜底，避免大语料全表相似度计算。
     */
    @Select("""
            SELECT c.id                       AS chunkId,
                   c.document_id              AS documentId,
                   c.chunk_index              AS chunkIndex,
                   c.content                  AS content,
                   c.token_count              AS tokenCount,
                   COALESCE(ts_rank(c.tsv, q.query), 0)  AS ftsRank,
                   COALESCE(similarity(c.content, #{query}), 0) AS trgmSim
              FROM kb_chunks c
              CROSS JOIN websearch_to_tsquery('simple', #{query}) q
             WHERE c.user_id = #{userId}
               AND (c.tsv @@ q.query OR similarity(c.content, #{query}) > #{minTrgm})
             ORDER BY ftsRank DESC, trgmSim DESC
             LIMIT #{limit}
            """)
    List<Map<String, Object>> searchHybrid(@Param("userId") Long userId,
                                           @Param("query") String query,
                                           @Param("minTrgm") double minTrgm,
                                           @Param("limit") int limit);

    /** pgvector 可用性探测：extname='vector' 存在即视为可启用向量路。 */
    @Select("SELECT COUNT(*) > 0 FROM pg_extension WHERE extname = 'vector'")
    boolean pgvectorAvailable();
}
