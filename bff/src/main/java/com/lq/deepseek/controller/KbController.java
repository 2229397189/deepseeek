package com.lq.deepseek.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lq.deepseek.common.Result;
import com.lq.deepseek.dto.KbDtos;
import com.lq.deepseek.service.KbService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库入口：文档管理 + 混合检索。
 */
@Tag(name = "知识库")
@RestController
@RequestMapping("/kb")
@RequiredArgsConstructor
public class KbController {

    private final KbService kbService;

    @Operation(summary = "文档列表")
    @GetMapping("/documents")
    public Result<List<KbDtos.KbDocument>> documents(@RequestParam(required = false) String title,
                                                   @RequestParam(required = false) Long documentId) {
        return Result.ok(kbService.listDocuments(StpUtil.getLoginIdAsLong(), title, documentId));
    }

    @Operation(summary = "上传文档并切片建库")
    @PostMapping("/documents")
    public Result<KbDtos.KbDocument> upload(@RequestParam("file") MultipartFile file) {
        return Result.ok(kbService.uploadDocument(StpUtil.getLoginIdAsLong(), file));
    }

    @Operation(summary = "混合检索")
    @GetMapping("/search")
    public Result<List<KbDtos.KbHit>> search(@RequestParam String q,
                                            @RequestParam(defaultValue = "10") int topK) {
        return Result.ok(kbService.search(StpUtil.getLoginIdAsLong(), q, topK));
    }

    @Operation(summary = "触发重建索引")
    @PostMapping("/reindex")
    public Result<KbDtos.KbReindexResult> reindex() {
        return Result.ok(kbService.reindex(StpUtil.getLoginIdAsLong()));
    }
}
