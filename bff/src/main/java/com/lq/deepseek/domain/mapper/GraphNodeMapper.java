package com.lq.deepseek.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lq.deepseek.domain.entity.GraphNode;
import org.apache.ibatis.annotations.Mapper;

/** 知识图谱节点 Mapper（表 graph_node 由 V5 创建）。 */
@Mapper
public interface GraphNodeMapper extends BaseMapper<GraphNode> {
}
