package com.lq.deepseek.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lq.deepseek.common.Result;
import com.lq.deepseek.dto.ResumeDtos;
import com.lq.deepseek.service.ResumeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 简历中心入口。
 */
@Tag(name = "简历中心")
@RestController
@RequestMapping("/resume")
@RequiredArgsConstructor
public class ResumeController {

    private final ResumeService resumeService;

    @Operation(summary = "上传简历并触发结构化解析")
    @PostMapping("/upload")
    public Result<ResumeDtos.ResumeVO> upload(@RequestParam("file") MultipartFile file) {
        return Result.ok(resumeService.uploadResume(StpUtil.getLoginIdAsLong(), file));
    }

    @Operation(summary = "我的简历列表")
    @GetMapping("/list")
    public Result<List<ResumeDtos.AssetBrief>> list() {
        return Result.ok(resumeService.listResumes(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "简历画像详情")
    @GetMapping("/{assetId}")
    public Result<ResumeDtos.ResumeVO> detail(@PathVariable Long assetId) {
        return Result.ok(resumeService.getResume(StpUtil.getLoginIdAsLong(), assetId));
    }

    @Operation(summary = "选中文本润色 / 按岗位定制生成")
    @PostMapping("/polish")
    public Result<ResumeDtos.PolishVO> polish(@Valid @RequestBody ResumeDtos.PolishRequest request) {
        return Result.ok(resumeService.polish(StpUtil.getLoginIdAsLong(), request));
    }

    @Operation(summary = "保存简历正文（Markdown）")
    @PutMapping("/{assetId}")
    public Result<ResumeDtos.SaveBodyResult> saveBody(@PathVariable Long assetId,
                                                      @Valid @RequestBody ResumeDtos.SaveBodyRequest request) {
        return Result.ok(resumeService.saveBody(StpUtil.getLoginIdAsLong(), assetId, request.getBody()));
    }
}
