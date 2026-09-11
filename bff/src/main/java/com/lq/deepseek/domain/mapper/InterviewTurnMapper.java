package com.lq.deepseek.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lq.deepseek.domain.entity.InterviewTurn;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 面试轮次消息 Mapper（表 interview_turns 由 V1 创建）。 */
@Mapper
public interface InterviewTurnMapper extends BaseMapper<InterviewTurn> {

    /** 取会话内当前最大 seq（并发下由 uk_turn_session_seq 兜底唯一性）。 */
    @Select("SELECT COALESCE(MAX(seq), 0) FROM interview_turns WHERE session_id = #{sessionId}")
    int selectMaxSeq(@Param("sessionId") Long sessionId);
}
