package com.lq.deepseek.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lq.deepseek.common.BusinessException;
import com.lq.deepseek.common.ErrorCode;
import com.lq.deepseek.domain.entity.ModelConfig;
import com.lq.deepseek.domain.entity.PricingRule;
import com.lq.deepseek.domain.mapper.ModelConfigMapper;
import com.lq.deepseek.domain.mapper.PricingRuleMapper;
import com.lq.deepseek.dto.AdminDtos;
import com.lq.deepseek.service.AdminService;
import com.lq.deepseek.service.support.ModelHealthProbe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 系统管理实现：模型配置 + 计费规则。
 *
 * <p>安全约束：apiKey 为敏感字段，落库原文，但<b>所有读取接口一律脱敏</b>（仅保留尾部掩码），
 * 且本类任何路径都不得将其写入日志；连通性探测失败只回传错误摘要，不回传密钥。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private final ModelConfigMapper modelConfigMapper;
    private final PricingRuleMapper pricingRuleMapper;
    private final ModelHealthProbe healthProbe;

    @Override
    public List<AdminDtos.ModelConfig> listModels() {
        return modelConfigMapper.selectList(new LambdaQueryWrapper<ModelConfig>()
                .orderByDesc(ModelConfig::getCreatedAt)).stream()
                .map(this::toDTO)
                .toList();
    }

    @Override
    public AdminDtos.ModelConfig createModel(AdminDtos.ModelConfigRequest request) {
        ModelConfig entity = new ModelConfig();
        entity.setName(request.getName());
        entity.setProvider(request.getProvider());
        entity.setBaseUrl(request.getBaseUrl());
        entity.setModel(request.getModel());
        // 写入原文；脱敏在读取时完成
        entity.setApiKey(request.getApiKey());
        entity.setEnabled(request.getEnabled() == null || request.getEnabled());
        modelConfigMapper.insert(entity);
        return toDTO(entity);
    }

    @Override
    public AdminDtos.ModelConfig updateModel(Long id, AdminDtos.ModelConfigRequest request) {
        ModelConfig entity = modelConfigMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "模型配置不存在");
        }
        if (StringUtils.hasText(request.getName())) {
            entity.setName(request.getName());
        }
        if (StringUtils.hasText(request.getProvider())) {
            entity.setProvider(request.getProvider());
        }
        if (request.getBaseUrl() != null) {
            entity.setBaseUrl(request.getBaseUrl());
        }
        if (StringUtils.hasText(request.getModel())) {
            entity.setModel(request.getModel());
        }
        // apiKey 仅在显式传入非空时更新，避免误清空
        if (StringUtils.hasText(request.getApiKey())) {
            entity.setApiKey(request.getApiKey());
        }
        if (request.getEnabled() != null) {
            entity.setEnabled(request.getEnabled());
        }
        modelConfigMapper.updateById(entity);
        return toDTO(entity);
    }

    @Override
    public void deleteModel(Long id) {
        if (modelConfigMapper.selectById(id) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "模型配置不存在");
        }
        modelConfigMapper.deleteById(id);
    }

    @Override
    public AdminDtos.ModelTestResult testModel(Long id) {
        ModelConfig entity = modelConfigMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "模型配置不存在");
        }
        AdminDtos.ModelTestResult result = new AdminDtos.ModelTestResult();
        if (!StringUtils.hasText(entity.getBaseUrl())) {
            result.setOk(true);
            return result;
        }
        ModelHealthProbe.ProbeResult probe = healthProbe.ping(entity.getBaseUrl());
        result.setOk(probe.isOk());
        result.setLatencyMs(probe.getLatencyMs());
        result.setError(probe.getError());
        return result;
    }

    @Override
    public List<AdminDtos.PricingRule> listPricing() {
        return pricingRuleMapper.selectList(new LambdaQueryWrapper<PricingRule>()
                .orderByAsc(PricingRule::getBizType)).stream()
                .map(r -> {
                    AdminDtos.PricingRule vo = new AdminDtos.PricingRule();
                    vo.setBizType(r.getBizType());
                    vo.setUnitCredit(r.getUnitCredit());
                    vo.setDescription(r.getDescription());
                    return vo;
                })
                .toList();
    }

    /** apiKey 脱敏：保留尾部 4 位，其余以 * 掩码。 */
    private String maskApiKey(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        if (raw.length() <= 4) {
            return "****";
        }
        return "****" + raw.substring(raw.length() - 4);
    }

    private AdminDtos.ModelConfig toDTO(ModelConfig entity) {
        AdminDtos.ModelConfig vo = new AdminDtos.ModelConfig();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setProvider(entity.getProvider());
        vo.setBaseUrl(entity.getBaseUrl());
        vo.setModel(entity.getModel());
        vo.setApiKey(maskApiKey(entity.getApiKey()));
        vo.setEnabled(entity.getEnabled());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
