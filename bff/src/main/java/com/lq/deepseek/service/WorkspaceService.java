package com.lq.deepseek.service;

import com.lq.deepseek.dto.ModelsDtos;
import com.lq.deepseek.dto.WorkspaceDtos;

/**
 * 工作台服务：意图识别 + 可用模型。
 */
public interface WorkspaceService {

    /** 识别输入文本的意图并给出目标路由与建议。 */
    WorkspaceDtos.IntentResult intent(String text);

    /** 可用模型列表（供 ModelPicker 使用）。 */
    ModelsDtos.AvailableModels availableModels();
}
