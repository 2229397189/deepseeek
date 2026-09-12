package com.lq.deepseek.service;

import com.lq.deepseek.dto.KbDtos;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库服务：文档管理 + 混合检索（向量 + 全文）。
 */
public interface KbService {

    /** 文档列表（按标题 / 文档 ID 过滤）。 */
    List<KbDtos.KbDocument> listDocuments(Long userId, String title, Long documentId);

    /** 上传文档并切片建库。 */
    KbDtos.KbDocument uploadDocument(Long userId, MultipartFile file);

    /** 混合检索：优先走 RAG_SEARCH 网关，失败回退确定性混合打分。 */
    List<KbDtos.KbHit> search(Long userId, String query, int topK);

    /** 触发重建索引（异步由 agent 完成，此处仅受理）。 */
    KbDtos.KbReindexResult reindex(Long userId);

    /** 删除文档（逻辑删除文档 + 物理删除其切片/向量）。 */
    void deleteDocument(Long userId, Long documentId);
}
