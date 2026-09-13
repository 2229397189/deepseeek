package com.lq.deepseek.service;

import com.lq.deepseek.dto.DecisionDtos;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * JD 分析服务。
 */
public interface DecisionService {

    /** 上传 JD 文件并抽取全文（pdf / docx / md / txt）。 */
    DecisionDtos.JdUploadVO uploadJd(Long userId, MultipartFile file);

    /** 上传 JD：支持文件，也支持前端「粘贴 JD 文本」直接传入 text。 */
    DecisionDtos.JdUploadVO uploadJd(Long userId, MultipartFile file, String text);

    /** 快速预览：只要分数与缺口，落库但不进历史结论。 */
    DecisionDtos.PreviewVO preview(Long userId, DecisionDtos.PreviewRequest request);

    /** 正式分析：写会话、写明细、写时间线消息。 */
    DecisionDtos.SessionDetailVO analyze(Long userId, DecisionDtos.AnalyzeRequest request);

    List<DecisionDtos.SessionBrief> listSessions(Long userId);

    DecisionDtos.SessionDetailVO detail(Long userId, Long sessionId);

    /** 针对该 JD 追问：走知识库混合检索，答案必须落在 JD / 简历 / 分析结论的原文上。 */
    DecisionDtos.AskVO ask(Long userId, Long sessionId, DecisionDtos.AskRequest request);

    /** 归档（逻辑删除）会话，历史分析随之从列表消失但数据保留。 */
    void archive(Long userId, Long sessionId);
}
