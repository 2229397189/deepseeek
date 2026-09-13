package com.lq.deepseek.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lq.deepseek.common.BusinessException;
import com.lq.deepseek.common.ErrorCode;
import com.lq.deepseek.config.props.LqProperties;
import com.lq.deepseek.domain.entity.KbChunk;
import com.lq.deepseek.domain.entity.KbDocument;
import com.lq.deepseek.domain.mapper.KbChunkMapper;
import com.lq.deepseek.domain.mapper.KbDocumentMapper;
import com.lq.deepseek.dto.KbDtos;
import com.lq.deepseek.gateway.AiInvocationGateway;
import com.lq.deepseek.gateway.model.AgentInvokeCommand;
import com.lq.deepseek.gateway.model.AgentInvokeResult;
import com.lq.deepseek.service.KbService;
import com.lq.deepseek.service.support.HybridRetriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库实现：文档上传切片 + 混合检索（向量 + 全文）。
 *
 * <p>检索优先走网关 RAG_SEARCH（agent 负责向量召回与 RRF 融合）；网关不可用时回退到
 * <b>确定性混合打分</b>：基于词项重叠同时给出 vectorScore 与 ftsScore，保证前端两种分数都有值。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbServiceImpl implements KbService {

    static final String BIZ = "RAG_SEARCH";
    static final String SPEC = "kb-search-v1";
    static final String DOC_BIZ = "KB_DOC";

    static final long SEARCH_FREEZE_CREDIT = 3L;
    static final long SEARCH_TIMEOUT_MS = 30_000L;
    static final int CHUNK_SIZE = 500;
    static final int DEFAULT_TOP_K = 10;

    static final Set<String> ALLOWED_EXTS = Set.of("pdf", "docx", "md", "txt");

    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final AiInvocationGateway gateway;
    private final LqProperties properties;
    private final HybridRetriever hybridRetriever;

    @Override
    public List<KbDtos.KbDocument> listDocuments(Long userId, String title, Long documentId) {
        LambdaQueryWrapper<KbDocument> wrapper = new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getUserId, userId)
                .eq(KbDocument::getDeleted, 0);
        if (documentId != null) {
            wrapper.eq(KbDocument::getId, documentId);
        }
        if (StringUtils.hasText(title)) {
            wrapper.like(KbDocument::getTitle, title.trim());
        }
        wrapper.orderByDesc(KbDocument::getUpdatedAt).last("LIMIT 100");
        return documentMapper.selectList(wrapper).stream().map(this::toDTO).toList();
    }

    @Override
    public KbDtos.KbDocument uploadDocument(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_EMPTY);
        }
        String fileName = StringUtils.cleanPath(
                file.getOriginalFilename() == null ? "kb.txt" : file.getOriginalFilename());
        String ext = extensionOf(fileName);
        if (!ALLOWED_EXTS.contains(ext)) {
            throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED,
                    "知识库仅支持 pdf / docx / md / txt，当前为 ." + (ext.isEmpty() ? "未知" : ext));
        }
        long maxSize = properties.getStorage().getMaxFileSize();
        if (file.getSize() > maxSize) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE,
                    "文件超过 " + (maxSize / 1024 / 1024) + "MB 上限");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取上传文件失败", e);
        }

        KbDocument document = new KbDocument();
        document.setUserId(userId);
        document.setTitle(fileName);
        document.setDocType("OTHER");
        document.setStatus("INDEXING");
        document.setChunkCount(0);
        documentMapper.insert(document);

        String text = new String(bytes, StandardCharsets.UTF_8);
        List<String> chunks = chunkText(text);
        int index = 0;
        for (String chunk : chunks) {
            KbChunk entity = new KbChunk();
            entity.setDocumentId(document.getId());
            entity.setUserId(userId);
            entity.setBizType(DOC_BIZ);
            entity.setChunkIndex(index++);
            entity.setContent(chunk);
            entity.setTokenCount(Math.max(1, chunk.length() / 2));
            chunkMapper.insert(entity);
        }

        document.setStatus("READY");
        document.setChunkCount(chunks.size());
        documentMapper.updateById(document);

        log.info("知识库文档入库 userId={} docId={} chunks={}", userId, document.getId(), chunks.size());
        return toDTO(document);
    }

    @Override
    public List<KbDtos.KbHit> search(Long userId, String query, int topK) {
        if (!StringUtils.hasText(query)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "检索关键词不能为空");
        }
        int k = topK > 0 ? topK : DEFAULT_TOP_K;

        AgentInvokeResult result = callGateway(userId, query, k);
        if (result != null && result.succeeded()) {
            List<KbDtos.KbHit> hits = parseHits(result.getOutput().get("hits"));
            if (hits != null && !hits.isEmpty()) {
                return hits.stream().limit(k).toList();
            }
        }
        // 网关不可用 / agent 无结果 → 数据库内混合检索（FTS + trigram + RRF，向两路就绪时自动三路）
        log.info("知识库检索走库内混合检索（RRF 融合） userId={} q={}", userId, query);
        try {
            List<KbDtos.KbHit> hybrid = hybridRetriever.search(userId, query, k, null);
            if (!hybrid.isEmpty()) {
                return hybrid;
            }
        } catch (Exception e) {
            // pg_trgm / tsquery 不可用等环境差异：再降级为纯 Java 词项重叠打分
            log.warn("库内混合检索不可用，回退词项重叠打分 userId={} 原因={}", userId, e.getMessage());
        }
        return fallbackSearch(userId, query, k);
    }

    @Override
    public KbDtos.KbReindexResult reindex(Long userId) {
        // 重建索引由 agent 异步完成；此处仅受理并返回受理信号（BFF 侧不持有向量计算）。
        KbDtos.KbReindexResult result = new KbDtos.KbReindexResult();
        result.setAccepted(true);
        return result;
    }

    @Override
    public void deleteDocument(Long userId, Long documentId) {
        if (documentId == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "缺少文档 ID");
        }
        KbDocument document = documentMapper.selectOne(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getId, documentId)
                .eq(KbDocument::getUserId, userId)
                .eq(KbDocument::getDeleted, 0));
        if (document == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        // kb_chunks 无 deleted 列 → 物理删除切片；kb_documents 有 deleted 列 → 逻辑删除文档
        chunkMapper.delete(new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getDocumentId, documentId)
                .eq(KbChunk::getUserId, userId));
        documentMapper.deleteById(documentId);
        log.info("知识库文档已删除 userId={} documentId={}（切片同步清理）", userId, documentId);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private AgentInvokeResult callGateway(Long userId, String query, int topK) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("topK", topK);
        // 语料级 RetrievalProfile：随请求透传给 agent，融合权重与 RRF 参数一份配置两处复用
        var r = properties.getKb().getRetrieval();
        payload.put("profile", Map.of(
                "wFts", r.getWFts(),
                "wTrgm", r.getWTrgm(),
                "wVector", r.getWVector(),
                "rrfK", r.getRrfK(),
                "topK", topK));
        try {
            return gateway.invoke(AgentInvokeCommand.builder()
                    .bizType(BIZ)
                    .userId(userId)
                    .stage("SEARCH")
                    .specHash(SPEC)
                    .payload(payload)
                    .expectedCredit(SEARCH_FREEZE_CREDIT)
                    .timeoutMs(SEARCH_TIMEOUT_MS)
                    .build());
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.INSUFFICIENT_CREDIT) {
                throw e;
            }
            return null;
        }
    }

    /** 确定性混合打分：词项重叠同时驱动 vectorScore 与 ftsScore，二者均回填。 */
    private List<KbDtos.KbHit> fallbackSearch(Long userId, String query, int topK) {
        List<KbChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getUserId, userId)
                .last("LIMIT 1000"));
        Map<Long, String> docTitles = documentMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                        .eq(KbDocument::getUserId, userId)
                        .eq(KbDocument::getDeleted, 0))
                .stream().collect(Collectors.toMap(KbDocument::getId, KbDocument::getTitle, (a, b) -> a));

        Set<String> qTokens = tokenize(query);
        List<Scored> scored = new ArrayList<>();
        for (KbChunk chunk : chunks) {
            Set<String> cTokens = tokenize(chunk.getContent());
            if (qTokens.isEmpty() || cTokens.isEmpty()) {
                continue;
            }
            long overlap = qTokens.stream().filter(cTokens::contains).count();
            double ftsScore = overlap * 10.0;
            double vectorScore = qTokens.size() == 0 ? 0
                    : (overlap * 100.0 / qTokens.size());
            double score = (vectorScore + ftsScore) / 2.0;
            if (overlap == 0) {
                continue;
            }
            scored.add(new Scored(chunk, score, vectorScore, ftsScore));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        List<KbDtos.KbHit> hits = new ArrayList<>();
        for (Scored s : scored.stream().limit(topK).toList()) {
            KbDtos.KbHit hit = new KbDtos.KbHit();
            hit.setDocumentId(s.chunk.getDocumentId());
            hit.setTitle(docTitles.getOrDefault(s.chunk.getDocumentId(), "未命名文档"));
            hit.setContent(s.chunk.getContent());
            hit.setScore(round(s.score));
            hit.setVectorScore(round(s.vectorScore));
            hit.setFtsScore(round(s.ftsScore));
            hits.add(hit);
        }
        return hits;
    }

    @SuppressWarnings("unchecked")
    private List<KbDtos.KbHit> parseHits(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<KbDtos.KbHit> hits = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) map;
            KbDtos.KbHit hit = new KbDtos.KbHit();
            hit.setDocumentId(asLong(m.get("documentId")));
            hit.setTitle(strOf(m.get("title"), "未命名文档"));
            hit.setContent(strOf(m.get("content"), ""));
            hit.setScore(asDouble(m.get("score")));
            hit.setVectorScore(asDouble(m.get("vectorScore")));
            hit.setFtsScore(asDouble(m.get("ftsScore")));
            hits.add(hit);
        }
        return hits;
    }

    private List<String> chunkText(String text) {
        List<String> chunks = new ArrayList<>();
        if (!StringUtils.hasText(text)) {
            return chunks;
        }
        StringBuilder buffer = new StringBuilder();
        for (String line : text.split("\\R", -1)) {
            if (buffer.length() + line.length() > CHUNK_SIZE && buffer.length() > 0) {
                chunks.add(buffer.toString().trim());
                buffer.setLength(0);
            }
            buffer.append(line).append('\n');
            while (buffer.length() > CHUNK_SIZE) {
                chunks.add(buffer.substring(0, CHUNK_SIZE).trim());
                buffer.delete(0, CHUNK_SIZE);
            }
        }
        if (buffer.length() > 0) {
            chunks.add(buffer.toString().trim());
        }
        if (chunks.isEmpty()) {
            chunks.add(text.trim());
        }
        return chunks;
    }

    private static Set<String> tokenize(String text) {
        Set<String> tokens = new java.util.HashSet<>();
        if (text == null) {
            return tokens;
        }
        for (String raw : text.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (raw.length() > 1) {
                tokens.add(raw);
            }
        }
        return tokens;
    }

    private KbDtos.KbDocument toDTO(KbDocument doc) {
        KbDtos.KbDocument vo = new KbDtos.KbDocument();
        vo.setDocumentId(doc.getId());
        vo.setTitle(doc.getTitle());
        vo.setChunkCount(doc.getChunkCount());
        vo.setUpdatedAt(doc.getUpdatedAt());
        return vo;
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String strOf(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private static Long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private static Double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }

    /** 打分中间态。 */
    private record Scored(KbChunk chunk, double score, double vectorScore, double ftsScore) {
    }
}
