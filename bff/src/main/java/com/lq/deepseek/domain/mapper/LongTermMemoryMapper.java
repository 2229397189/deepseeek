package com.lq.deepseek.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lq.deepseek.domain.entity.LongTermMemory;
import org.apache.ibatis.annotations.Mapper;

/** 长期记忆 Mapper（表 long_term_memory 由 V5 创建）。 */
@Mapper
public interface LongTermMemoryMapper extends BaseMapper<LongTermMemory> {
}
