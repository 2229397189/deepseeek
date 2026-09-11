package com.lq.deepseek.service;

import com.lq.deepseek.dto.AdminDtos;

import java.util.List;

/**
 * 系统管理服务：模型配置 + 计费规则。
 */
public interface AdminService {

    /** 模型配置列表（apiKey 脱敏）。 */
    List<AdminDtos.ModelConfig> listModels();

    /** 新增模型配置。 */
    AdminDtos.ModelConfig createModel(AdminDtos.ModelConfigRequest request);

    /** 更新模型配置（apiKey 仅在传入非空时更新）。 */
    AdminDtos.ModelConfig updateModel(Long id, AdminDtos.ModelConfigRequest request);

    /** 删除模型配置。 */
    void deleteModel(Long id);

    /** 探测模型连通性。 */
    AdminDtos.ModelTestResult testModel(Long id);

    /** 计费规则列表。 */
    List<AdminDtos.PricingRule> listPricing();
}
