package com.lq.deepseek.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lq.deepseek.domain.entity.InterviewSession;
import org.apache.ibatis.annotations.Mapper;

/** 面试会话 Mapper（表 interview_sessions 由 V1 创建）。 */
@Mapper
public interface InterviewSessionMapper extends BaseMapper<InterviewSession> {
}
