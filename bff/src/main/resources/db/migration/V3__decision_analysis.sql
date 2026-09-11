-- =====================================================================
-- V2 JD 分析（Decision）模块：会话补充字段 + 分析结果明细
-- 设计原则：
--   1) 会话（decision_sessions）只承载"主题与最新状态"，明细一律另表，列表查询不读大字段
--   2) 分析结果保留原始 run_id 与费用，保证"看到的分数能追到是哪次 AI 调用产出的"
--   3) JD 全文只在会话上存一份（重分析不再向前端要一次文本），明细表只留摘录
-- =====================================================================

ALTER TABLE decision_sessions ADD COLUMN resume_asset_id  BIGINT;
ALTER TABLE decision_sessions ADD COLUMN jd_asset_id      BIGINT;
ALTER TABLE decision_sessions ADD COLUMN job_title        VARCHAR(128);
ALTER TABLE decision_sessions ADD COLUMN jd_text          TEXT;
ALTER TABLE decision_sessions ADD COLUMN latest_score     INT;
ALTER TABLE decision_sessions ADD COLUMN latest_analysis_id BIGINT;

ALTER TABLE decision_sessions
    ADD CONSTRAINT fk_decision_resume FOREIGN KEY (resume_asset_id) REFERENCES file_assets (id);
ALTER TABLE decision_sessions
    ADD CONSTRAINT fk_decision_jd FOREIGN KEY (jd_asset_id) REFERENCES file_assets (id);

COMMENT ON COLUMN decision_sessions.latest_score IS '最近一次 JD 匹配分，列表页免于回表查明细';
COMMENT ON COLUMN decision_sessions.jd_text IS '本次分析的 JD 原文，支持"调整简历后一键重算"';

-- ---------------------------------------------------------------------
-- JD 分析明细
-- ---------------------------------------------------------------------
CREATE TABLE decision_analyses (
    id                BIGINT      PRIMARY KEY,
    session_id        BIGINT      NOT NULL,
    user_id           BIGINT      NOT NULL,
    mode              VARCHAR(16) NOT NULL DEFAULT 'FULL',
    run_id            VARCHAR(64),
    resume_asset_id   BIGINT,
    jd_asset_id       BIGINT,
    jd_excerpt        TEXT,
    score             INT,
    score_band        VARCHAR(16),
    conclusion        VARCHAR(32),
    dimensions        JSONB,
    required_skills   JSONB,
    covered_skills    JSONB,
    missing_skills    JSONB,
    weak_points_hit   JSONB,
    hard_requirements JSONB,
    risks             JSONB,
    todos             JSONB,
    advice            TEXT,
    cost_credit       BIGINT      NOT NULL DEFAULT 0,
    latency_ms        INT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_analysis_session FOREIGN KEY (session_id) REFERENCES decision_sessions (id),
    CONSTRAINT fk_analysis_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_analysis_mode CHECK (mode IN ('FULL', 'PREVIEW'))
);
CREATE INDEX idx_analysis_session_created ON decision_analyses (session_id, created_at DESC);
CREATE INDEX idx_analysis_user_created ON decision_analyses (user_id, created_at DESC);
COMMENT ON TABLE decision_analyses IS 'JD 匹配分析明细，mode=PREVIEW 为快速预览（不计入历史结论）';

-- 追问消息按会话顺序发送，seq 唯一约束在 V1 已建（uk_decision_msg_seq）
CREATE INDEX idx_decision_msg_session ON decision_messages (session_id, seq);
