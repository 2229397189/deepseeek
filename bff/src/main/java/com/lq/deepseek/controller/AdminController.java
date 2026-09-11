package com.lq.deepseek.controller;

import com.lq.deepseek.common.Result;
import com.lq.deepseek.dto.AdminDtos;
import com.lq.deepseek.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 系统管理入口：模型配置 + 计费规则。
 */
@Tag(name = "系统管理")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @Operation(summary = "模型配置列表")
    @GetMapping("/models")
    public Result<List<AdminDtos.ModelConfig>> models() {
        return Result.ok(adminService.listModels());
    }

    @Operation(summary = "新增模型配置")
    @PostMapping("/models")
    public Result<AdminDtos.ModelConfig> createModel(@Valid @RequestBody AdminDtos.ModelConfigRequest request) {
        return Result.ok(adminService.createModel(request));
    }

    @Operation(summary = "更新模型配置")
    @PutMapping("/models/{id}")
    public Result<AdminDtos.ModelConfig> updateModel(@PathVariable Long id,
                                                    @Valid @RequestBody AdminDtos.ModelConfigRequest request) {
        return Result.ok(adminService.updateModel(id, request));
    }

    @Operation(summary = "删除模型配置")
    @DeleteMapping("/models/{id}")
    public Result<Void> deleteModel(@PathVariable Long id) {
        adminService.deleteModel(id);
        return Result.ok();
    }

    @Operation(summary = "探测模型连通性")
    @PostMapping("/models/{id}/test")
    public Result<AdminDtos.ModelTestResult> testModel(@PathVariable Long id) {
        return Result.ok(adminService.testModel(id));
    }

    @Operation(summary = "计费规则列表")
    @GetMapping("/pricing")
    public Result<List<AdminDtos.PricingRule>> pricing() {
        return Result.ok(adminService.listPricing());
    }
}
