-- =====================================================================
-- V6 简历版本管理：支持 Markdown 正文的版本快照与回滚。
-- 设计原则：每次保存正文落一条 version 记录，version_no 自增。
-- 回滚 = 用历史 version 的 body 创建新 version。
-- =====================================================================

CREATE TABLE resume_version (
    id           BIGINT       PRIMARY KEY,
    asset_id     BIGINT       NOT NULL,
    user_id      BIGINT       NOT NULL,
    version_no   INT          NOT NULL DEFAULT 1,
    body         TEXT         NOT NULL,
    change_desc  VARCHAR(255),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_rv_asset FOREIGN KEY (asset_id) REFERENCES file_assets (id),
    CONSTRAINT fk_rv_user  FOREIGN KEY (user_id)  REFERENCES users (id),
    CONSTRAINT uk_rv_asset_ver UNIQUE (asset_id, version_no)
);

CREATE INDEX idx_rv_asset ON resume_version (asset_id, version_no DESC);
COMMENT ON TABLE resume_version IS '简历正文版本快照，每次保存落一条';
