package com.lq.deepseek.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lq.deepseek.common.Result;
import com.lq.deepseek.dto.ProfileDtos;
import com.lq.deepseek.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户画像 / 长期记忆入口。
 */
@Tag(name = "用户画像")
@RestController
@RequestMapping("/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    @Operation(summary = "聚合能力标签")
    @GetMapping("/tags")
    public Result<List<ProfileDtos.CapabilityTag>> tags() {
        return Result.ok(profileService.tags(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "长期记忆列表")
    @GetMapping("/memories")
    public Result<List<ProfileDtos.LongTermMemory>> memories() {
        return Result.ok(profileService.memories(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "确认一条记忆")
    @PostMapping("/memories/{id}/confirm")
    public Result<Void> confirm(@PathVariable Long id) {
        profileService.confirmMemory(StpUtil.getLoginIdAsLong(), id);
        return Result.ok();
    }

    @Operation(summary = "修正一条记忆")
    @PostMapping("/memories/{id}/correct")
    public Result<Void> correct(@PathVariable Long id,
                               @Valid @RequestBody ProfileDtos.MemoryCorrectRequest request) {
        profileService.correctMemory(StpUtil.getLoginIdAsLong(), id, request.getCorrectedContent());
        return Result.ok();
    }
}
