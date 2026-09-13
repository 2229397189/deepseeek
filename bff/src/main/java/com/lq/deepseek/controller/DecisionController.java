package com.lq.deepseek.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lq.deepseek.common.Result;
import com.lq.deepseek.dto.DecisionDtos;
import com.lq.deepseek.service.DecisionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * JD 分析入口。
 */
@Tag(name = "JD 分析")
@RestController
@RequestMapping("/decision")
@RequiredArgsConstructor
public class DecisionController {

    private final DecisionService decisionService;

    @Operation(summary = "上传 JD 文件并抽取全文")
    @PostMapping("/jd/upload")
    public Result<DecisionDtos.JdUploadVO> uploadJd(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "text", required = false) String text) {
        return Result.ok(decisionService.uploadJd(StpUtil.getLoginIdAsLong(), file, text));
    }

    @Operation(summary = "快速预览：只给分数与缺口")
    @PostMapping("/preview")
    public Result<DecisionDtos.PreviewVO> preview(@Valid @RequestBody DecisionDtos.PreviewRequest request) {
        return Result.ok(decisionService.preview(StpUtil.getLoginIdAsLong(), request));
    }

    @Operation(summary = "正式分析（新建或继续会话）")
    @PostMapping("/analyze")
    public Result<DecisionDtos.SessionDetailVO> analyze(@Valid @RequestBody DecisionDtos.AnalyzeRequest request) {
        return Result.ok(decisionService.analyze(StpUtil.getLoginIdAsLong(), request));
    }

    @Operation(summary = "我的分析会话列表")
    @GetMapping("/sessions")
    public Result<List<DecisionDtos.SessionBrief>> sessions() {
        return Result.ok(decisionService.listSessions(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "会话详情（分析历史 + 时间线）")
    @GetMapping("/sessions/{sessionId}")
    public Result<DecisionDtos.SessionDetailVO> detail(@PathVariable Long sessionId) {
        return Result.ok(decisionService.detail(StpUtil.getLoginIdAsLong(), sessionId));
    }

    @Operation(summary = "针对该 JD 追问")
    @PostMapping("/sessions/{sessionId}/ask")
    public Result<DecisionDtos.AskVO> ask(@PathVariable Long sessionId,
                                          @Valid @RequestBody DecisionDtos.AskRequest request) {
        return Result.ok(decisionService.ask(StpUtil.getLoginIdAsLong(), sessionId, request));
    }

    @Operation(summary = "归档分析会话")
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> archive(@PathVariable Long sessionId) {
        decisionService.archive(StpUtil.getLoginIdAsLong(), sessionId);
        return Result.ok();
    }
}
