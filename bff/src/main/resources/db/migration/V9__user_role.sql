-- 用户角色：admin 可访问 /admin/**（模型管理、知识库、治理指标），其余用户一律 'user'。
-- 存量数据：首个注册用户（id=1）升为 admin，作为初始管理员。
ALTER TABLE users ADD COLUMN role VARCHAR(32) NOT NULL DEFAULT 'user';

UPDATE users SET role = 'admin' WHERE id = 1;

COMMENT ON COLUMN users.role IS '角色：user 普通用户 / admin 管理员（/admin/** 需要 admin）';
