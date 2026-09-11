package com.lq.deepseek.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lq.deepseek.domain.entity.DecisionSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DecisionSessionMapper extends BaseMapper<DecisionSession> {

    /**
     * 会话终态收敛：最新 run / 最新明细 / 最新分数 / 状态一次写完。
     *
     * <p>不用 updateById 是因为这些字段只在"一次分析成功"这一刻整组变化，
     * 拆成多次 update 会在并发重算时出现"分数已更新、明细还指向旧行"的中间态。
     */
    @Update("""
            UPDATE decision_sessions
               SET status             = #{status},
                   latest_run_id      = #{runId},
                   latest_analysis_id = #{analysisId},
                   latest_score       = #{score},
                   title              = COALESCE(NULLIF(#{title}, ''), title),
                   job_title          = COALESCE(NULLIF(#{jobTitle}, ''), job_title),
                   updated_at         = now()
             WHERE id = #{id}
               AND deleted = 0
            """)
    int markAnalyzed(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("runId") String runId,
                     @Param("analysisId") Long analysisId,
                     @Param("score") Integer score,
                     @Param("title") String title,
                     @Param("jobTitle") String jobTitle);

    /** 分析开始前落 RUNNING，让"分析中"这一状态对列表页可见。 */
    @Update("""
            UPDATE decision_sessions
               SET status = #{status}, updated_at = now()
             WHERE id = #{id}
               AND deleted = 0
            """)
    int markStatus(@Param("id") Long id, @Param("status") String status);
}
