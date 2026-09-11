package com.lq.deepseek.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lq.deepseek.domain.entity.KbChunk;
import org.apache.ibatis.annotations.Mapper;

/** 知识库分片 Mapper（表 kb_chunks 由 V2 创建）。 */
@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {
}
