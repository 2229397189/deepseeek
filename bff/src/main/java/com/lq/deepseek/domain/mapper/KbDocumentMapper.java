package com.lq.deepseek.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lq.deepseek.domain.entity.KbDocument;
import org.apache.ibatis.annotations.Mapper;

/** 知识库文档 Mapper（表 kb_documents 由 V2 创建）。 */
@Mapper
public interface KbDocumentMapper extends BaseMapper<KbDocument> {
}
