package com.lq.deepseek.controller;

import com.lq.deepseek.common.Result;
import com.lq.deepseek.dto.ModelsDtos;
import com.lq.deepseek.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 可用模型入口（供前端 ModelPicker 拉取）。
 */
@Tag(name = "模型可用")
@RestController
@RequestMapping("/models")
@RequiredArgsConstructor
public class ModelsController {

    private final WorkspaceService workspaceService;

    @Operation(summary = "可用模型列表")
    @GetMapping("/available")
    public Result<ModelsDtos.AvailableModels> available() {
        return Result.ok(workspaceService.availableModels());
    }
}
