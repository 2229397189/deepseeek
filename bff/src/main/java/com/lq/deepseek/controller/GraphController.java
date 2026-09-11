package com.lq.deepseek.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lq.deepseek.common.Result;
import com.lq.deepseek.dto.GraphDtos;
import com.lq.deepseek.service.GraphService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识图谱入口。
 */
@Tag(name = "知识图谱")
@RestController
@RequestMapping("/graph")
@RequiredArgsConstructor
public class GraphController {

    private final GraphService graphService;

    @Operation(summary = "图谱节点")
    @GetMapping("/nodes")
    public Result<List<GraphDtos.GraphNode>> nodes() {
        return Result.ok(graphService.nodes(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "图谱边")
    @GetMapping("/edges")
    public Result<List<GraphDtos.GraphEdge>> edges() {
        return Result.ok(graphService.edges(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "节点证据")
    @GetMapping("/nodes/{id}/evidence")
    public Result<List<GraphDtos.Evidence>> evidence(@PathVariable String id) {
        return Result.ok(graphService.evidence(StpUtil.getLoginIdAsLong(), id));
    }
}
