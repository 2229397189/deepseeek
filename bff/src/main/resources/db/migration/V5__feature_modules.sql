-- =====================================================================
-- V5 新增能力模块表：面试报告 / 长期记忆 / 能力标签 / 知识图谱 / 模型与计费配置
-- 注意：interview_sessions / interview_turns（V1）、kb_documents / kb_chunks（V2）
-- 已在先前迁移中创建，本迁移只补齐其余缺失表，避免重复建表导致 Flyway 失败。
-- 设计原则：雪花主键（与全局 id-type=assign_id 一致）、created_at/updated_at、
-- 文本类用 text、结构化用 jsonb、布尔用 bool。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 面试报告（每场面试结束后落一份终态报告，分数永远可追溯到具体 AI 调用）
-- ---------------------------------------------------------------------
CREATE TABLE interview_reports (
    id           BIGINT       PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    session_id   BIGINT       NOT NULL,
    score        INT,
    dimensions   JSONB,
    weak_points  JSONB,
    suggestion   TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_report_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_report_session FOREIGN KEY (session_id) REFERENCES interview_sessions (id)
);
CREATE INDEX idx_report_session ON interview_reports (session_id);
COMMENT ON TABLE interview_reports IS 'AI 模拟面试终态报告，分数/维度/薄弱点/建议';

-- ---------------------------------------------------------------------
-- 长期记忆（来自决策/面试产出的可确认记忆条目，用户可确认或修正）
-- ---------------------------------------------------------------------
CREATE TABLE long_term_memory (
    id         BIGINT       PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    content    TEXT         NOT NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_ltm_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_ltm_status CHECK (status IN ('PENDING', 'CONFIRMED', 'CORRECTED'))
);
CREATE INDEX idx_ltm_user ON long_term_memory (user_id, created_at DESC);
COMMENT ON TABLE long_term_memory IS '用户长期记忆，状态机 PENDING -> CONFIRMED / CORRECTED';

-- ---------------------------------------------------------------------
-- 能力标签（聚合自简历技能 + 决策分析 + 面试报告，供画像与图谱复用）
-- ---------------------------------------------------------------------
CREATE TABLE capability_tag (
    id         BIGINT       PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    tag        VARCHAR(128) NOT NULL,
    category   VARCHAR(32),
    level      VARCHAR(32),
    source     VARCHAR(32),
    confidence DOUBLE PRECISION,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_tag_user FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE INDEX idx_tag_user ON capability_tag (user_id, source);
COMMENT ON TABLE capability_tag IS '能力标签，category: SKILL/STRENGTH/GAP/WEAK，source: RESUME/DECISION/INTERVIEW';

-- ---------------------------------------------------------------------
-- 知识图谱节点 / 边（前端力导向图的直接数据源）
-- ---------------------------------------------------------------------
CREATE TABLE graph_node (
    id         VARCHAR(64)  PRIMARY KEY,
    label      VARCHAR(255) NOT NULL,
    type       VARCHAR(32)  NOT NULL,
    status     VARCHAR(16),
    layer      INT          NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_graph_node_type CHECK (type IN ('CANDIDATE', 'RESUME', 'JOB', 'INTERVIEW', 'SKILL', 'PROJECT', 'POSITION')),
    CONSTRAINT ck_graph_node_status CHECK (status IS NULL OR status IN ('WEAK', 'NORMAL'))
);
CREATE INDEX idx_graph_node_type ON graph_node (type);
COMMENT ON TABLE graph_node IS '知识图谱节点，id 为业务键（如 resume:123 / skill:Java）';

CREATE TABLE graph_edge (
    id         BIGINT      PRIMARY KEY,
    source     VARCHAR(64) NOT NULL,
    target     VARCHAR(64) NOT NULL,
    relation   VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_edge_source FOREIGN KEY (source) REFERENCES graph_node (id),
    CONSTRAINT fk_edge_target FOREIGN KEY (target) REFERENCES graph_node (id)
);
CREATE INDEX idx_graph_edge_source ON graph_edge (source);
COMMENT ON TABLE graph_edge IS '知识图谱边，source/target 引用 graph_node.id';

-- ---------------------------------------------------------------------
-- 模型配置（多模型接入：DeepSeek / OpenAI 兼容）
-- ---------------------------------------------------------------------
CREATE TABLE model_config (
    id         BIGINT       PRIMARY KEY,
    name       VARCHAR(128) NOT NULL,
    provider   VARCHAR(64)  NOT NULL,
    base_url   VARCHAR(512),
    model      VARCHAR(128) NOT NULL,
    api_key    VARCHAR(512),
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_model_enabled ON model_config (enabled);
COMMENT ON COLUMN model_config.api_key IS '敏感字段，落库原文，读取接口一律脱敏';

-- ---------------------------------------------------------------------
-- 计费规则（与 AiInvocationGateway 的冻结额度口径一致）
-- ---------------------------------------------------------------------
CREATE TABLE pricing_rule (
    id           BIGINT       PRIMARY KEY,
    biz_type     VARCHAR(32)  NOT NULL,
    unit_credit  BIGINT       NOT NULL DEFAULT 0,
    description  VARCHAR(255),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_pricing_biz UNIQUE (biz_type)
);
COMMENT ON TABLE pricing_rule IS '各业务类型的单次调用基准额度';

-- 默认计费规则：id 用固定常量，避免依赖数据库序列（应用侧使用雪花 ID，互不冲突）
INSERT INTO pricing_rule (id, biz_type, unit_credit, description) VALUES
    (910001, 'DECIDE',         12, 'JD 正式匹配分析'),
    (910002, 'DECIDE_PREVIEW',  4, 'JD 快速预览'),
    (910003, 'INTERVIEW',       8, 'AI 模拟面试（每轮次）'),
    (910004, 'RESUME_PARSE',    10, '简历解析'),
    (910005, 'RESUME_QUESTION', 5, '简历润色 / 定制生成'),
    (910006, 'RAG_SEARCH',      3, '知识库混合检索')
ON CONFLICT (biz_type) DO NOTHING;
