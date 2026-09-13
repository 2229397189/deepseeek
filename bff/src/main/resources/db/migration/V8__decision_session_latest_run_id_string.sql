-- decision_sessions.latest_run_id 初始被误建成 BIGINT，但业务写入的是网关生成的字符串 run_id
-- （形如 "run_<snowflake>"，见 AiInvocationGateway）。字符串写入 BIGINT 列会触发
-- "invalid input syntax for type bigint"，导致 /decision/analyze 在落库阶段抛出未捕获异常（500001），
-- 会话卡在 RUNNING、latest_analysis_id 为空。改为 VARCHAR(64) 与 agent_runs / credit_ledger /
-- decision_analyses 的 run_id 类型保持一致。
ALTER TABLE decision_sessions
    ALTER COLUMN latest_run_id TYPE VARCHAR(64) USING latest_run_id::TEXT;

COMMENT ON COLUMN decision_sessions.latest_run_id IS
    '最近一次分析对应的 AI 调用 run_id（字符串，与 agent_runs.run_id 对齐，用于分数溯源）';
