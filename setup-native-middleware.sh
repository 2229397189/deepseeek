#!/usr/bin/env bash
# setup-native-middleware.sh — 用 dnf 在本机装好 PostgreSQL 13 + Redis，供 `deploy.sh --no-docker` 使用。
#
# 适用场景：服务器**没有公网出口**，拉不到 Docker Hub 镜像（pgvector/pgvector:pg16、redis:7-alpine），
#          也访问不了 PGDG；但能访问发行版自带的内网软件源（如阿里云 ECS 的 mirrors.cloud.aliyuncs.com）。
#
# 做四件事：
#   1) dnf 安装 postgresql-server / postgresql-contrib / redis
#   2) 把 pip 指向内网 PyPI 源（写 /etc/pip.conf）—— 否则 agent-service 装依赖必失败
#   3) 初始化并启动 PostgreSQL / Redis（含 pg_hba 认证方式修正）
#   4) 建角色 / 库 / 预建 pg_trgm 扩展 / 交出 public schema
#
# 幂等：可重复执行。请用 root 运行。

set -uo pipefail

DB_NAME="${DB_NAME:-lq_deepseek}"
DB_USER="${DB_USER:-lq}"
DB_PASS="${DB_PASS:-lq_deepseek_pwd}"
# 内网 PyPI 源（阿里云 ECS 内网，HTTP；如需换成公网源，改这两个变量即可）
PYPI_HOST="${PYPI_HOST:-mirrors.cloud.aliyuncs.com}"

PGDATA_DIR="/var/lib/pgsql/data"
HBA="$PGDATA_DIR/pg_hba.conf"

log() { printf '\033[34m[setup]\033[0m %s\n' "$*"; }
ok()  { printf '\033[32m[ ok ]\033[0m %s\n' "$*"; }
err() { printf '\033[31m[err ]\033[0m %s\n' "$*" >&2; }

[ "$(id -u)" = "0" ] || { err "请用 root 执行"; exit 1; }

# ---------------------------------------------------------------------------
# 1) 安装系统包
# ---------------------------------------------------------------------------
log "安装 postgresql-server / postgresql-contrib / redis"
dnf install -y postgresql-server postgresql-contrib redis || { err "dnf 安装失败"; exit 1; }
ok "系统包安装完成"

# ---------------------------------------------------------------------------
# 2) pip 内网源（agent-service 依赖 Python 包，无公网时必须换源）
# ---------------------------------------------------------------------------
if [ ! -f /etc/pip.conf ]; then
  log "写入 /etc/pip.conf → $PYPI_HOST"
  cat > /etc/pip.conf <<PIPCONF
[global]
index-url = http://${PYPI_HOST}/pypi/simple/
trusted-host = ${PYPI_HOST}
timeout = 60
PIPCONF
  ok "pip 已指向内网源（HTTP，故需 trusted-host）"
else
  log "/etc/pip.conf 已存在，跳过（如需改源请手动编辑）"
fi

# ---------------------------------------------------------------------------
# 3) 初始化并启动 PostgreSQL / Redis
# ---------------------------------------------------------------------------
if [ ! -s "$PGDATA_DIR/PG_VERSION" ]; then
  log "初始化 PostgreSQL 数据目录"
  postgresql-setup --initdb || { err "postgresql-setup --initdb 失败"; exit 1; }
else
  log "PostgreSQL 数据目录已初始化，跳过 initdb"
fi

# 发行版默认 pg_hba.conf 对 127.0.0.1 / ::1 用 ident；JDBC 走 TCP+密码会认证失败，改成 md5。
if grep -qE '^host[[:space:]]+.*[[:space:]]ident[[:space:]]*$' "$HBA" 2>/dev/null; then
  log "修正 pg_hba.conf：host 行的 ident → md5"
  sed -i -E 's/^(host[[:space:]]+.*[[:space:]])ident[[:space:]]*$/\1md5/' "$HBA"
  ok "pg_hba.conf 已调整"
else
  log "pg_hba.conf 无需调整"
fi

systemctl enable --now postgresql || { err "postgresql 启动失败"; exit 1; }
systemctl enable --now redis || { err "redis 启动失败"; exit 1; }
ok "postgresql / redis 已启动并设为开机自启"

log "等待 PostgreSQL 接受连接…"
for _i in $(seq 1 30); do pg_isready -q && break; sleep 1; done
pg_isready -q || { err "PostgreSQL 15s 内未就绪，请 journalctl -u postgresql 排查"; exit 1; }

# ---------------------------------------------------------------------------
# 4) 角色 / 库 / 扩展 / schema 权限
# ---------------------------------------------------------------------------
log "创建角色 ${DB_USER} 与数据库 ${DB_NAME}"
if ! su - postgres -c "psql -tAc \"SELECT 1 FROM pg_roles WHERE rolname='${DB_USER}'\"" | grep -q 1; then
  su - postgres -c "psql -v ON_ERROR_STOP=1 -c \"CREATE ROLE ${DB_USER} LOGIN PASSWORD '${DB_PASS}'\"" \
    || { err "创建角色失败"; exit 1; }
  ok "角色 ${DB_USER} 已创建"
else
  log "角色 ${DB_USER} 已存在，跳过"
fi

if ! su - postgres -c "psql -tAc \"SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'\"" | grep -q 1; then
  su - postgres -c "createdb -O ${DB_USER} ${DB_NAME}" || { err "创建数据库失败"; exit 1; }
  ok "数据库 ${DB_NAME} 已创建（owner=${DB_USER}）"
else
  log "数据库 ${DB_NAME} 已存在，跳过"
fi

# pg_trgm 需要超级用户创建，且必须在应用账号建表前就存在（V1 里 CREATE EXTENSION IF NOT EXISTS 才不会报权限错）
su - postgres -c "psql -v ON_ERROR_STOP=1 -d ${DB_NAME} -c 'CREATE EXTENSION IF NOT EXISTS pg_trgm'" \
  || { err "创建 pg_trgm 扩展失败"; exit 1; }
ok "扩展 pg_trgm 就绪"

# PG13 起 public schema 默认不给普通角色 CREATE 权限，交给应用账号
su - postgres -c "psql -v ON_ERROR_STOP=1 -d ${DB_NAME} -c 'ALTER SCHEMA public OWNER TO ${DB_USER}'" \
  || { err "移交 public schema 失败"; exit 1; }
ok "public schema 已归属 ${DB_USER}"

# ---------------------------------------------------------------------------
# 5) 验收：用应用账号走 TCP + 密码连一次（与 BFF 的连接方式一致）
# ---------------------------------------------------------------------------
log "验收：以 ${DB_USER} 通过 127.0.0.1 + 密码连接"
if PGPASSWORD="$DB_PASS" psql -h 127.0.0.1 -U "$DB_USER" -d "$DB_NAME" -tAc \
     "SELECT 'pg_ok=' || current_user, string_agg(extname, ',' ORDER BY extname) FROM pg_extension" 2>&1; then
  ok "PostgreSQL TCP+密码认证通过"
else
  err "应用账号连接失败（检查 pg_hba.conf 与密码）"
  exit 1
fi

if [ "$(redis-cli ping 2>/dev/null)" = "PONG" ]; then
  ok "Redis 响应 PONG"
else
  err "Redis 未响应"
  exit 1
fi

echo
ok "本机中间件就绪。下一步执行： cd $(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd) && ./deploy.sh --no-docker"
