package com.lq.deepseek.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lq.deepseek.domain.entity.AgentRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRun> {

    @Select("SELECT * FROM agent_runs WHERE run_id = #{runId} LIMIT 1")
    AgentRun selectByRunId(@Param("runId") String runId);

    /**
     * 取同一输入摘要下最近一次成功的运行，用于串联「同输入多调用」的排查链路。
     */
    @Select("""
            SELECT *
              FROM agent_runs
             WHERE input_digest = #{digest}
               AND status = 'SUCCEEDED'
             ORDER BY created_at DESC
             LIMIT 1
            """)
    AgentRun selectLatestSucceededByDigest(@Param("digest") String digest);

    /**
     * 收尾写终态。output_json 以字符串传入并显式转 jsonb，避免驱动把 jsonb 列当成 varchar 处理。
     */
    @Update("""
            UPDATE agent_runs
               SET status        = #{status},
                   attempt       = #{attempt},
                   output        = #{outputJson}::jsonb,
                   error_code    = #{errorCode},
                   error_msg     = #{errorMsg},
                   prompt_tokens = #{promptTokens},
                   output_tokens = #{outputTokens},
                   cost_credit   = #{costCredit},
                   latency_ms    = #{latencyMs},
                   finished_at   = now(),
                   updated_at    = now()
             WHERE run_id = #{runId}
            """)
    int finish(@Param("runId") String runId,
               @Param("status") String status,
               @Param("attempt") int attempt,
               @Param("outputJson") String outputJson,
               @Param("errorCode") String errorCode,
               @Param("errorMsg") String errorMsg,
               @Param("promptTokens") int promptTokens,
               @Param("outputTokens") int outputTokens,
               @Param("costCredit") long costCredit,
               @Param("latencyMs") int latencyMs);
}
