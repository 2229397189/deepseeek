package com.lq.deepseek.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lq.deepseek.domain.entity.AgentContextSnapshot;
import org.apache.ibatis.annotations.Mapper;

/** Agent Context 快照 Mapper（表 agent_context_snapshots 由 V2 创建）。 */
@Mapper
public interface AgentContextSnapshotMapper extends BaseMapper<AgentContextSnapshot> {
}
