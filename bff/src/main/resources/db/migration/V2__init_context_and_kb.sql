-- =====================================================================
-- V2 Context 治理 与 知识库混合检索
--   - agent_context_snapshots / agent_context_slices：可追踪的上下文打包
--   - kb_documents / kb_chunks：pgvector 向量 + PostgreSQL FTS 双路召回
-- =====================================================================

CREATE TABLE agent_context_snapshots (
    id           BIGINT      PRIMARY KEY,
    user_id      BIGINT      NOT NULL,
    session_id   BIGINT,
    biz_type     VARCHAR(32) NOT NULL,
    token_budget INT         NOT NULL DEFAULT 8000,
    token_used   INT         NOT NULL DEFAULT 0,
    degraded     BOOLEAN     NOT NULL DEFAULT FALSE,
    degraded_reason VARCHAR(255),
    snapshot     JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_snapshot_user FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE INDEX idx_snapshot_user_biz ON agent_context_snapshots (user_id, biz_type, created_at DESC);

CREATE TABLE agent_context_slices (
    id             BIGINT      PRIMARY KEY,
    snapshot_id    BIGINT      NOT NULL,
    slice_type     VARCHAR(32) NOT NULL,
    evidence_level VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    content        TEXT        NOT NULL,
    token_count    INT         NOT NULL DEFAULT 0,
    metadata       JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_slice_snapshot FOREIGN KEY (snapshot_id) REFERENCES agent_context_snapshots (id),
    CONSTRAINT ck_slice_type CHECK (slice_type IN (
        'RESUME_PROFILE', 'JD', 'HISTORY_INTERVIEW', 'LONG_TERM_MEMORY',
        'SESSION_MESSAGE', 'RETRIEVAL_CHUNK', 'USER_PREFERENCE')),
    CONSTRAINT ck_slice_evidence CHECK (evidence_level IN ('HIGH', 'MEDIUM', 'LOW'))
);
CREATE INDEX idx_slice_snapshot ON agent_context_slices (snapshot_id, slice_type);

CREATE TABLE kb_documents (
    id          BIGINT       PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    asset_id    BIGINT,
    title       VARCHAR(255) NOT NULL,
    doc_type    VARCHAR(32)  NOT NULL DEFAULT 'OTHER',
    status      VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    chunk_count INT          NOT NULL DEFAULT 0,
    error_msg   TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted     SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT fk_kbdoc_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_kbdoc_asset FOREIGN KEY (asset_id) REFERENCES file_assets (id),
    CONSTRAINT ck_kbdoc_status CHECK (status IN ('PENDING', 'INDEXING', 'READY', 'FAILED'))
);
CREATE INDEX idx_kbdoc_user ON kb_documents (user_id, created_at DESC) WHERE deleted = 0;

CREATE TABLE kb_chunks (
    id           BIGINT       PRIMARY KEY,
    document_id  BIGINT       NOT NULL,
    user_id      BIGINT       NOT NULL,
    session_id   BIGINT,
    biz_type     VARCHAR(32)  NOT NULL DEFAULT 'KB_DOC',
    chunk_index  INT          NOT NULL,
    content      TEXT         NOT NULL,
    token_count  INT          NOT NULL DEFAULT 0,
    embedding    VECTOR(1024),
    tsv          TSVECTOR     GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED,
    metadata     JSONB,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_chunk_doc FOREIGN KEY (document_id) REFERENCES kb_documents (id),
    CONSTRAINT uk_chunk_doc_index UNIQUE (document_id, chunk_index)
);
-- 向量检索：HNSW + 余弦距离
CREATE INDEX idx_chunk_embedding ON kb_chunks USING hnsw (embedding vector_cosine_ops);
-- 全文检索：GIN
CREATE INDEX idx_chunk_tsv ON kb_chunks USING gin (tsv);
-- 检索范围约束：按用户 / 会话 / 业务类型收窄
CREATE INDEX idx_chunk_scope ON kb_chunks (user_id, biz_type, session_id);
COMMENT ON COLUMN kb_chunks.embedding IS '1024 维向量，HNSW 余弦索引，供 RRF 融合的一路召回';
COMMENT ON COLUMN kb_chunks.tsv IS '生成的 tsvector 列，供 PostgreSQL FTS 一路召回';
