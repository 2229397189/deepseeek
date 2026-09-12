-- P0-6 面试出题引擎 NEXT 阶段 + questionPlan
-- interview_turns 需要承载「考察技能」与「逐题评估元数据」，
-- 前者用于 buildHistory 透传 skill 给 agent 做出题计划推进，
-- 后者用于落库命中/缺失关键词、是否追问、题号，供前端逐题得分展示。
ALTER TABLE interview_turns ADD COLUMN IF NOT EXISTS skill VARCHAR(64);
ALTER TABLE interview_turns ADD COLUMN IF NOT EXISTS eval_meta JSONB;
