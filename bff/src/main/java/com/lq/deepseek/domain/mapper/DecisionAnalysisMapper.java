package com.lq.deepseek.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lq.deepseek.domain.entity.DecisionAnalysis;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface DecisionAnalysisMapper extends BaseMapper<DecisionAnalysis> {

    /**
     * 会话维度的分析次数。
     *
     * <p>别名刻意加双引号：PostgreSQL 会把未加引号的别名折叠成小写，
     * 而 MyBatis 的 map key 区分大小写，不加引号会得到一个取不到值的 "sessionid"。
     */
    @Select("""
            SELECT session_id AS "sessionId", COUNT(*) AS "total"
              FROM decision_analyses
             WHERE user_id = #{userId}
             GROUP BY session_id
            """)
    List<Map<String, Object>> countByUser(@Param("userId") Long userId);
}
