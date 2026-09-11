package com.lq.deepseek.controller;

import com.lq.deepseek.common.Result;
import com.lq.deepseek.dto.WorkspaceDtos;
import com.lq.deepseek.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作台入口：意图识别。
 */
@Tag(name = "工作台")
@RestController
@RequestMapping("/workspace")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    @Operation(summary = "识别输入意图并给出目标路由")
    @PostMapping("/intent")
    public Result<WorkspaceDtos.IntentResult> intent(@Valid @RequestBody WorkspaceDtos.IntentRequest request) {
        return Result.ok(workspaceService.intent(request.getText()));
    }
}
