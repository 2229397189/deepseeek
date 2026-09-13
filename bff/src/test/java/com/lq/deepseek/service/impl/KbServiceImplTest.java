package com.lq.deepseek.service.impl;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
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
 * 知识库行为测试。
 *
 * <p>钉死三条：上传必须切片落库；检索优先走 RAG_SEARCH 网关；
 * 网关不可用时回退确定性混合打分，且 vectorScore 与 ftsScore 必须同时有值。
 */
class KbServiceImplTest {

    private static final Long USER_ID = 7L;

    private KbDocumentMapper documentMapper;
    private KbChunkMapper chunkMapper;
    private AiInvocationGateway gateway;
    private KbServiceImpl service;

    private final List<KbChunk> insertedChunks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        documentMapper = mock(KbDocumentMapper.class);
        chunkMapper = mock(KbChunkMapper.class);
        gateway = mock(AiInvocationGateway.class);
        service = new KbServiceImpl(documentMapper, chunkMapper, gateway, new LqProperties(),
                mock(HybridRetriever.class));

        when(documentMapper.insert(any(KbDocument.class))).thenAnswer(invocation -> {
            KbDocument doc = invocation.getArgument(0);
            doc.setId(1001L);
            return 1;
        });
        when(documentMapper.updateById(any(KbDocument.class))).thenReturn(1);
        when(chunkMapper.insert(any(KbChunk.class))).thenAnswer(invocation -> {
            insertedChunks.add(invocation.getArgument(0));
            return 1;
        });
    }

    @Test
    void uploadDocument_storesDocumentAndChunks() {
        String content = "Java 后端工程师。熟悉 Spring Boot 与 Redis。".repeat(40);
        MockMultipartFile file = new MockMultipartFile("file", "kb.txt", "text/plain",
                content.getBytes(StandardCharsets.UTF_8));

        KbDtos.KbDocument vo = service.uploadDocument(USER_ID, file);

        assertThat(vo.getDocumentId()).isEqualTo(1001L);
        assertThat(vo.getTitle()).isEqualTo("kb.txt");
        assertThat(vo.getChunkCount()).isGreaterThan(0);
        assertThat(insertedChunks).hasSize(vo.getChunkCount());
        assertThat(insertedChunks.get(0).getBizType()).isEqualTo("KB_DOC");

        ArgumentCaptor<KbDocument> doc = ArgumentCaptor.forClass(KbDocument.class);
        verify(documentMapper).updateById(doc.capture());
        assertThat(doc.getValue().getStatus()).isEqualTo("READY");
    }

    @Test
    void uploadDocument_unsupportedExtension_isRejected() {
        MockMultipartFile file = new MockMultipartFile("file", "kb.exe", "application/octet-stream",
                "binary".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.uploadDocument(USER_ID, file))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILE_TYPE_UNSUPPORTED);
        verify(documentMapper, org.mockito.Mockito.never()).insert(any(KbDocument.class));
    }

    @Test
    void search_gatewaySuccess_returnsRagHits() {
        when(gateway.invoke(any())).thenReturn(AgentInvokeResult.builder()
                .runId("run-rag").status("SUCCEEDED").flightMode("OWNER")
                .output(Map.of("hits", List.of(Map.of(
                        "documentId", 1001,
                        "title", "Java 面试题",
                        "content", "Java 内存模型",
                        "score", 0.9,
                        "vectorScore", 0.8,
                        "ftsScore", 0.7))))
                .build());

        List<KbDtos.KbHit> hits = service.search(USER_ID, "Java", 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getTitle()).isEqualTo("Java 面试题");
        assertThat(hits.get(0).getContent()).isEqualTo("Java 内存模型");
        assertThat(hits.get(0).getScore()).isEqualTo(0.9);
        assertThat(hits.get(0).getVectorScore()).isEqualTo(0.8);
        assertThat(hits.get(0).getFtsScore()).isEqualTo(0.7);

        ArgumentCaptor<AgentInvokeCommand> command = ArgumentCaptor.forClass(AgentInvokeCommand.class);
        verify(gateway).invoke(command.capture());
        assertThat(command.getValue().getBizType()).isEqualTo("RAG_SEARCH");
        assertThat(command.getValue().getPayload()).containsEntry("query", "Java");
    }

    @Test
    void search_gatewayFailure_fallsBackPopulatingBothScores() {
        when(gateway.invoke(any())).thenThrow(new BusinessException(ErrorCode.AGENT_UNAVAILABLE));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(1001L, "Java 高并发与 JVM 调优经验")));
        when(documentMapper.selectList(any())).thenReturn(List.of(document(1001L, "Java 简历")));

        List<KbDtos.KbHit> hits = service.search(USER_ID, "Java", 10);

        assertThat(hits).isNotEmpty();
        KbDtos.KbHit hit = hits.get(0);
        // 混合打分：向量分与全文分必须同时回填，前端才能做 RRF 展示
        assertThat(hit.getVectorScore()).isGreaterThan(0);
        assertThat(hit.getFtsScore()).isGreaterThan(0);
        assertThat(hit.getScore()).isGreaterThan(0);
        assertThat(hit.getTitle()).isEqualTo("Java 简历");
        assertThat(hit.getContent()).contains("Java");
    }

    @Test
    void search_blankQuery_isRejected() {
        assertThatThrownBy(() -> service.search(USER_ID, "  ", 10))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void reindex_returnsAccepted() {
        assertThat(service.reindex(USER_ID).getAccepted()).isTrue();
    }

    @Test
    void listDocuments_filtersByTitleAndDocumentId() {
        when(documentMapper.selectList(any())).thenReturn(List.of(document(1001L, "Java 简历")));

        List<KbDtos.KbDocument> docs = service.listDocuments(USER_ID, "Java", 1001L);

        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).getDocumentId()).isEqualTo(1001L);
        assertThat(docs.get(0).getTitle()).isEqualTo("Java 简历");
    }

    // ------------------------------------------------------------------

    private static KbDocument document(Long id, String title) {
        KbDocument doc = new KbDocument();
        doc.setId(id);
        doc.setUserId(USER_ID);
        doc.setTitle(title);
        doc.setStatus("READY");
        doc.setChunkCount(3);
        doc.setDeleted(0);
        return doc;
    }

    private static KbChunk chunk(Long documentId, String content) {
        KbChunk chunk = new KbChunk();
        chunk.setId(1L);
        chunk.setDocumentId(documentId);
        chunk.setUserId(USER_ID);
        chunk.setBizType("KB_DOC");
        chunk.setChunkIndex(0);
        chunk.setContent(content);
        return chunk;
    }
}
