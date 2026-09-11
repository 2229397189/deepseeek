package com.lq.deepseek.service;

import com.lq.deepseek.dto.ResumeDtos;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 简历中心服务。
 *
 * <p>所有 AI 能力（简历解析、润色）都必须经 {@code AiInvocationGateway} 进入 Python Agent，
 * 服务层只负责"文件落盘 + 状态机 + 业务语义"，不承担去重、重试、计量与审计。
 */
public interface ResumeService {

    /**
     * 上传简历并触发结构化解析。
     *
     * <p>同一用户上传内容相同的简历时直接复用既有资产（deduplicated=true），不重复扣费。
     */
    ResumeDtos.ResumeVO uploadResume(Long userId, MultipartFile file);

    /** 查询简历画像。 */
    ResumeDtos.ResumeVO getResume(Long userId, Long assetId);

    /** 当前用户的简历列表（按上传时间倒序）。 */
    List<ResumeDtos.AssetBrief> listResumes(Long userId);

    /** 选中文本润色 / 按岗位定制生成。 */
    ResumeDtos.PolishVO polish(Long userId, ResumeDtos.PolishRequest request);

    /** 保存简历正文（Markdown）。 */
    ResumeDtos.SaveBodyResult saveBody(Long userId, Long assetId, String body);
}
