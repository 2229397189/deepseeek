-- =====================================================================
-- V1 核心业务表：用户 / 文件资产 / 面试会话 / Decision 会话 / 计费钱包 / 邀请码
-- 设计原则：
--   1) 全表统一 id(bigint, 雪花)、created_at / updated_at、逻辑删除 deleted
--   2) 唯一约束与业务关联字段显式声明，保证多环境可重复初始化
--   3) 金额/额度统一用 bigint 整数分值，避免浮点误差
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS "pg_trgm";
CREATE EXTENSION IF NOT EXISTS "vector";

-- ---------------------------------------------------------------------
-- 用户
-- ---------------------------------------------------------------------
CREATE TABLE users (
    id            BIGINT       PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    email         VARCHAR(128),
    password_hash VARCHAR(128) NOT NULL,
    nickname      VARCHAR(64),
    avatar_url    VARCHAR(512),
    status        SMALLINT     NOT NULL DEFAULT 1,
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted       SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_users_username UNIQUE (username)
);
CREATE UNIQUE INDEX uk_users_email_active ON users (email) WHERE email IS NOT NULL AND deleted = 0;
CREATE INDEX idx_users_created_at ON users (created_at DESC);
COMMENT ON TABLE users IS '用户主表';
COMMENT ON COLUMN users.password_hash IS 'BCrypt 摘要，禁止存明文';

-- ---------------------------------------------------------------------
-- 文件资产（简历 / JD / 知识库文档）
-- ---------------------------------------------------------------------
CREATE TABLE file_assets (
    id           BIGINT       PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    biz_type     VARCHAR(32)  NOT NULL,
    file_name    VARCHAR(255) NOT NULL,
    object_key   VARCHAR(512) NOT NULL,
    content_type VARCHAR(128),
    size_bytes   BIGINT       NOT NULL DEFAULT 0,
    sha256       CHAR(64)     NOT NULL,
    parse_status VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    parse_result JSONB,
    error_msg    TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted      SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT fk_file_assets_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_file_assets_biz CHECK (biz_type IN ('RESUME', 'JD', 'KB_DOC')),
    CONSTRAINT ck_file_assets_parse CHECK (parse_status IN ('PENDING', 'PARSING', 'SUCCESS', 'FAILED')),
    CONSTRAINT ck_file_assets_status_deleted CHECK (parse_status <> 'FAILED' OR error_msg IS NOT NULL)
);
CREATE UNIQUE INDEX uk_file_assets_user_sha ON file_assets (user_id, sha256) WHERE deleted = 0;
CREATE INDEX idx_file_assets_user_biz ON file_assets (user_id, biz_type, created_at DESC);
COMMENT ON COLUMN file_assets.biz_type IS 'RESUME=简历, JD=职位描述, KB_DOC=知识库文档';
COMMENT ON COLUMN file_assets.parse_result IS '解析结构化结果，含画像/技能/项目等字段';

-- ---------------------------------------------------------------------
-- 面试会话（Python Agent 为状态权威，BFF 存投影快照）
-- ---------------------------------------------------------------------
CREATE TABLE interview_sessions (
    id               BIGINT      PRIMARY KEY,
    user_id          BIGINT      NOT NULL,
    resume_asset_id  BIGINT,
    jd_asset_id      BIGINT,
    title            VARCHAR(128) NOT NULL,
    mode             VARCHAR(24) NOT NULL DEFAULT 'TEXT',
    status           VARCHAR(24) NOT NULL DEFAULT 'CREATED',
    current_stage    VARCHAR(32),
    turn_count       INT         NOT NULL DEFAULT 0,
    state_snapshot   JSONB,
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted          SMALLINT    NOT NULL DEFAULT 0,
    CONSTRAINT fk_interview_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_interview_resume FOREIGN KEY (resume_asset_id) REFERENCES file_assets (id),
    CONSTRAINT fk_interview_jd FOREIGN KEY (jd_asset_id) REFERENCES file_assets (id),
    CONSTRAINT ck_interview_mode CHECK (mode IN ('TEXT', 'VIDEO')),
    CONSTRAINT ck_interview_status CHECK (status IN ('CREATED', 'RUNNING', 'PAUSED', 'FINISHED', 'ABORTED'))
);
CREATE INDEX idx_interview_user_status ON interview_sessions (user_id, status, created_at DESC);
CREATE INDEX idx_interview_user_created ON interview_sessions (user_id, created_at DESC) WHERE deleted = 0;

CREATE TABLE interview_turns (
    id          BIGINT      PRIMARY KEY,
    session_id  BIGINT      NOT NULL,
    seq         INT         NOT NULL,
    role        VARCHAR(16) NOT NULL,
    stage       VARCHAR(32),
    content     TEXT        NOT NULL,
    score       NUMERIC(5, 2),
    latency_ms  INT,
    token_usage JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_turn_session FOREIGN KEY (session_id) REFERENCES interview_sessions (id),
    CONSTRAINT ck_turn_role CHECK (role IN ('INTERVIEWER', 'CANDIDATE', 'SYSTEM')),
    CONSTRAINT uk_turn_session_seq UNIQUE (session_id, seq)
);
CREATE INDEX idx_turn_session_created ON interview_turns (session_id, created_at);

-- ---------------------------------------------------------------------
-- Decision 会话
-- ---------------------------------------------------------------------
CREATE TABLE decision_sessions (
    id             BIGINT       PRIMARY KEY,
    user_id        BIGINT       NOT NULL,
    title          VARCHAR(128) NOT NULL,
    scene          VARCHAR(32)  NOT NULL DEFAULT 'OFFER',
    status         VARCHAR(24)  NOT NULL DEFAULT 'CREATED',
    snapshot_id    BIGINT,
    latest_run_id  BIGINT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted        SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT fk_decision_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_decision_status CHECK (status IN ('CREATED', 'RUNNING', 'FINISHED', 'FAILED'))
);
CREATE INDEX idx_decision_user_created ON decision_sessions (user_id, created_at DESC) WHERE deleted = 0;

CREATE TABLE decision_messages (
    id         BIGINT      PRIMARY KEY,
    session_id BIGINT      NOT NULL,
    seq        INT         NOT NULL,
    role       VARCHAR(16) NOT NULL,
    content    TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_decision_msg_session FOREIGN KEY (session_id) REFERENCES decision_sessions (id),
    CONSTRAINT uk_decision_msg_seq UNIQUE (session_id, seq)
);

-- ---------------------------------------------------------------------
-- AI 调用运行记录（Agent Invocation Gateway 的落库审计与结果回放）
-- ---------------------------------------------------------------------
CREATE TABLE agent_runs (
    id            BIGINT       PRIMARY KEY,
    run_id        VARCHAR(64)  NOT NULL,
    user_id       BIGINT       NOT NULL,
    biz_type      VARCHAR(32)  NOT NULL,
    biz_id        BIGINT,
    stage         VARCHAR(32),
    status        VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    spec_hash     VARCHAR(64),
    input_digest  VARCHAR(64)  NOT NULL,
    attempt       INT          NOT NULL DEFAULT 0,
    owner_instance VARCHAR(64),
    output        JSONB,
    error_code    VARCHAR(64),
    error_msg     TEXT,
    prompt_tokens INT          NOT NULL DEFAULT 0,
    output_tokens INT          NOT NULL DEFAULT 0,
    cost_credit   BIGINT       NOT NULL DEFAULT 0,
    latency_ms    INT          NOT NULL DEFAULT 0,
    started_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_run_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_run_run_id UNIQUE (run_id),
    CONSTRAINT ck_run_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED')),
    CONSTRAINT ck_run_biz CHECK (biz_type IN ('DECIDE', 'INTERVIEW', 'RESUME_PARSE', 'RESUME_QUESTION', 'DECIDE_PREVIEW', 'RAG_SEARCH'))
);
CREATE INDEX idx_run_flight ON agent_runs (biz_type, input_digest, created_at DESC);
CREATE INDEX idx_run_user_created ON agent_runs (user_id, created_at DESC);
CREATE INDEX idx_run_status_heartbeat ON agent_runs (status, updated_at);

-- ---------------------------------------------------------------------
-- 计费钱包 & credit 明细
-- ---------------------------------------------------------------------
CREATE TABLE billing_wallets (
    id               BIGINT      PRIMARY KEY,
    user_id          BIGINT      NOT NULL,
    balance_credit   BIGINT      NOT NULL DEFAULT 0,
    frozen_credit    BIGINT      NOT NULL DEFAULT 0,
    total_granted    BIGINT      NOT NULL DEFAULT 0,
    total_consumed   BIGINT      NOT NULL DEFAULT 0,
    version          INT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_wallet_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_wallet_user UNIQUE (user_id),
    CONSTRAINT ck_wallet_balance CHECK (balance_credit >= 0 AND frozen_credit >= 0)
);
COMMENT ON COLUMN billing_wallets.frozen_credit IS 'AI 调用预授权冻结额度，结束后按实际消耗结算';

CREATE TABLE credit_ledger (
    id              BIGINT      PRIMARY KEY,
    user_id         BIGINT      NOT NULL,
    wallet_id       BIGINT      NOT NULL,
    change_type     VARCHAR(24) NOT NULL,
    amount          BIGINT      NOT NULL,
    balance_after   BIGINT      NOT NULL,
    biz_type        VARCHAR(32),
    biz_id          BIGINT,
    run_id          VARCHAR(64),
    idempotency_key VARCHAR(128) NOT NULL,
    remark          VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_ledger_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_ledger_wallet FOREIGN KEY (wallet_id) REFERENCES billing_wallets (id),
    CONSTRAINT uk_ledger_idem UNIQUE (idempotency_key),
    CONSTRAINT ck_ledger_type CHECK (change_type IN ('RECHARGE', 'GRANT', 'CONSUME', 'REFUND', 'FREEZE', 'UNFREEZE')),
    CONSTRAINT ck_ledger_amount CHECK (amount <> 0)
);
CREATE INDEX idx_ledger_user_created ON credit_ledger (user_id, created_at DESC);
CREATE INDEX idx_ledger_run ON credit_ledger (run_id);

-- ---------------------------------------------------------------------
-- 邀请码
-- ---------------------------------------------------------------------
CREATE TABLE invite_codes (
    id              BIGINT      PRIMARY KEY,
    code            VARCHAR(32) NOT NULL,
    inviter_user_id BIGINT      NOT NULL,
    max_uses        INT         NOT NULL DEFAULT 1,
    used_count      INT         NOT NULL DEFAULT 0,
    reward_credit   BIGINT      NOT NULL DEFAULT 0,
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    expire_at       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_invite_user FOREIGN KEY (inviter_user_id) REFERENCES users (id),
    CONSTRAINT uk_invite_code UNIQUE (code),
    CONSTRAINT ck_invite_status CHECK (status IN ('ACTIVE', 'EXHAUSTED', 'EXPIRED', 'DISABLED')),
    CONSTRAINT ck_invite_count CHECK (used_count >= 0 AND used_count <= max_uses)
);
CREATE INDEX idx_invite_user ON invite_codes (inviter_user_id, created_at DESC);
