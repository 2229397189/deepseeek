-- =====================================================================
-- 文件去重口径修正：去重边界从"用户 + 内容"收紧为"用户 + 业务中心 + 内容"
--
-- 背景：uk_file_assets_user_sha 只按 (user_id, sha256) 唯一，于是一份文件若既当简历传、
-- 又当 JD 传，第二次上传会命中第一次的资产行，JD 抽取结果会直接覆盖简历画像
-- （parse_result 语义被改写），简历中心随后就会报"该简历尚未解析成功"。
-- 两个业务中心对同一份文件的结构化口径本来就不同，必须各存一行。
-- =====================================================================

DROP INDEX IF EXISTS uk_file_assets_user_sha;

CREATE UNIQUE INDEX IF NOT EXISTS uk_file_assets_user_biz_sha
    ON file_assets (user_id, biz_type, sha256) WHERE deleted = 0;

COMMENT ON INDEX uk_file_assets_user_biz_sha IS '同一用户在同一个业务中心下，内容相同的文件只保留一行，避免跨中心覆盖 parse_result';
