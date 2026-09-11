package com.lq.deepseek.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lq.deepseek.domain.entity.DecisionMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DecisionMessageMapper extends BaseMapper<DecisionMessage> {

    @Select("SELECT COALESCE(MAX(seq), 0) FROM decision_messages WHERE session_id = #{sessionId}")
    int selectMaxSeq(@Param("sessionId") Long sessionId);
}
