package com.lq.deepseek.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lq.deepseek.domain.entity.ModelConfig;
import com.lq.deepseek.domain.mapper.ModelConfigMapper;
import com.lq.deepseek.dto.ModelsDtos;
import com.lq.deepseek.dto.WorkspaceDtos;
import com.lq.deepseek.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作台实现：意图识别（确定性关键词路由）+ 可用模型。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceServiceImpl implements WorkspaceService {

    private static final String DEFAULT_MODEL = "deepseek-chat";

    private final ModelConfigMapper modelConfigMapper;

    @Override
    public WorkspaceDtos.IntentResult intent(String text) {
        WorkspaceDtos.IntentResult result = new WorkspaceDtos.IntentResult();
        if (!StringUtils.hasText(text)) {
            result.setIntent("UNKNOWN");
            result.setTargetRoute(null);
            result.setSuggestions(List.of("上传简历", "粘贴 JD", "开始模拟面试"));
            return result;
        }
        String lower = text.toLowerCase();
        if (lower.contains("简历") || lower.contains("resume") || lower.contains("cv")) {
            result.setIntent("RESUME");
            result.setTargetRoute("/resume");
            result.setSuggestions(List.of("上传简历", "查看简历画像"));
        } else if (lower.contains("jd") || lower.contains("岗位") || lower.contains("招聘")
                || lower.contains("职位") || lower.contains("招聘需求")) {
            result.setIntent("JD");
            result.setTargetRoute("/decision");
            result.setSuggestions(List.of("粘贴 JD", "开始匹配分析"));
        } else if (lower.contains("面试") || lower.contains("interview")) {
            result.setIntent("INTERVIEW");
            result.setTargetRoute("/interview");
            result.setSuggestions(List.of("开始模拟面试"));
        } else {
            result.setIntent("UNKNOWN");
            result.setTargetRoute(null);
            result.setSuggestions(List.of("上传简历", "粘贴 JD", "开始模拟面试"));
        }
        return result;
    }

    @Override
    public ModelsDtos.AvailableModels availableModels() {
        List<ModelConfig> models = modelConfigMapper.selectList(new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getEnabled, true)
                .orderByDesc(ModelConfig::getCreatedAt)
                .last("LIMIT 50"));
        List<ModelsDtos.AvailableModel> available = new ArrayList<>();
        for (ModelConfig m : models) {
            ModelsDtos.AvailableModel am = new ModelsDtos.AvailableModel();
            am.setId(String.valueOf(m.getId()));
            am.setLabel(m.getName());
            am.setProvider(m.getProvider());
            available.add(am);
        }
        if (available.isEmpty()) {
            ModelsDtos.AvailableModel fallback = new ModelsDtos.AvailableModel();
            fallback.setId(DEFAULT_MODEL);
            fallback.setLabel("DeepSeek Chat");
            fallback.setProvider("deepseek");
            available.add(fallback);
        }
        ModelsDtos.AvailableModels vo = new ModelsDtos.AvailableModels();
        vo.setModels(available);
        vo.setDefaultModel(DEFAULT_MODEL);
        return vo;
    }
}
