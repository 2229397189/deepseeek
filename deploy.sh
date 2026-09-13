#!/usr/bin/env bash
# deploy.sh — Chiron Agent 一键部署（中间件 + 三服务 + 健康检查）
#
# 适用：Linux 服务器，已安装 docker / docker compose / JDK17 / Maven / Python3.11+ / Node18+
# 用法：
#   ./deploy.sh           启动中间件 + BFF + agent-service + web，并逐个健康检查
#   ./deploy.sh --stop    停止三个后台进程（不动 docker 中间件）
#   ./deploy.sh --down    停止三个后台进程，并 docker compose down（清空中间件容器与数据卷）
#
# 重要前提：BFF↔PG/Redis↔agent-service 全部用 127.0.0.1 互访，
#          因此 中间件 + BFF + agent + web 必须部署在【同一台机器】上。
#          agent-service 默认 AGENT_FORCE_MOCK=true（确定性模式，无需真实 LLM key 即可跑通）。

set -uo pipefail

# ---------------------------------------------------------------------------
# 颜色与日志
# ---------------------------------------------------------------------------
if [ -t 1 ]; then
  C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'; C_BLU=$'\033[34m'; C_RST=$'\033[0m'
else
  C_RED=""; C_GRN=""; C_YEL=""; C_BLU=""; C_RST=""
fi
log()  { printf '%s[deploy]%s %s\n' "$C_BLU" "$C_RST" "$*"; }
ok()   { printf '%s[ ok ]%s %s\n' "$C_GRN" "$C_RST" "$*"; }
warn() { printf '%s[warn]%s %s\n' "$C_YEL" "$C_RST" "$*"; }
err()  { printf '%s[err ]%s %s\n' "$C_RED" "$C_RST" "$*"; }

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
mkdir -p logs

# ---------------------------------------------------------------------------
# 健康检查辅助
# ---------------------------------------------------------------------------
wait_for_port() {
  local port="$1" timeout="${2:-120}" waited=0
  while ! (echo > "/dev/tcp/127.0.0.1/$port") >/dev/null 2>&1; do
    sleep 2; waited=$((waited + 2))
    if [ "$waited" -ge "$timeout" ]; then err "等待端口 $port 超时（${timeout}s）"; return 1; fi
  done
  return 0
}

wait_for_http() {
  local url="$1" timeout="${2:-120}" waited=0
  while ! curl -fsS -o /dev/null "$url" 2>/dev/null; do
    sleep 2; waited=$((waited + 2))
    if [ "$waited" -ge "$timeout" ]; then err "等待 $url 超时（${timeout}s）"; return 1; fi
  done
  return 0
}

# ---------------------------------------------------------------------------
# 前置依赖检查
# ---------------------------------------------------------------------------
need_cmd() {
  command -v "$1" >/dev/null 2>&1 || { err "缺少命令：$1，请先安装"; exit 1; }
}
need_cmd docker
need_cmd java
need_cmd python3
need_cmd curl

# ---------------------------------------------------------------------------
# 启动
# ---------------------------------------------------------------------------
start_all() {
  # 1) 中间件 ------------------------------------------------------------------
  log "启动中间件（PostgreSQL+pgvector / Redis）"
  docker compose up -d
  log "等待 PostgreSQL 就绪…"
  local t=0
  until docker compose exec -T postgres pg_isready -U lq -d lq_deepseek >/dev/null 2>&1; do
    sleep 2; t=$((t + 2))
    if [ "$t" -ge 120 ]; then err "PostgreSQL 120s 内未就绪，请 docker compose logs postgres 排查"; exit 1; fi
  done
  ok "PostgreSQL 就绪"
  log "等待 Redis 就绪…"
  until [ "$(docker compose exec -T redis redis-cli ping 2>/dev/null)" = "PONG" ]; do
    sleep 2; t=$((t + 2))
    if [ "$t" -ge 60 ]; then err "Redis 60s 内未就绪"; exit 1; fi
  done
  ok "Redis 就绪"

  # 2) BFF（Spring Boot / Maven，jar 在 bff/target/）---------------------------
  log "构建/启动 BFF（8080）"
  local jar
  jar="$(find bff/target -maxdepth 1 -name '*.jar' 2>/dev/null | head -1)"
  if [ -z "$jar" ]; then
    need_cmd mvn
    log "未找到 bff/target/*.jar，先用 Maven 打包（跳过测试）…"
    mvn -f bff/pom.xml -q package -DskipTests
    jar="$(find bff/target -maxdepth 1 -name '*.jar' 2>/dev/null | head -1)"
  fi
  [ -n "$jar" ] || { err "BFF 打包失败，未生成 jar"; exit 1; }
  nohup java -jar "$jar" > logs/bff.log 2>&1 &
  echo $! > logs/bff.pid
  wait_for_port 8080 180 && ok "BFF 端口 8080 已监听（日志 logs/bff.log）" \
    || { err "BFF 未起来，tail logs/bff.log 排查"; exit 1; }

  # 3) agent-service（FastAPI / Python venv）----------------------------------
  log "准备并启动 agent-service（8000）"
  cd agent-service
  if [ ! -d .venv ]; then
    log "创建 Python 虚拟环境并安装依赖…"
    python3 -m venv .venv
    .venv/bin/pip install -q -r requirements.txt
  fi
  # 默认离线确定性模式；若要接真实 LLM：export AGENT_FORCE_MOCK=false AGENT_LLM_API_KEY=sk-xxx
  local agent_mock="${AGENT_FORCE_MOCK:-true}"
  log "agent 模式：AGENT_FORCE_MOCK=$agent_mock"
  AGENT_FORCE_MOCK="$agent_mock" nohup .venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000 \
    > ../logs/agent.log 2>&1 &
  echo $! > ../logs/agent.pid
  cd "$ROOT"
  wait_for_http http://127.0.0.1:8000/health 120 && ok "agent-service /health 正常（日志 logs/agent.log）" \
    || { err "agent-service 未起来，tail logs/agent.log 排查"; exit 1; }

  # 4) web（node_modules 与 dist/ 都不入库，缺失则就地安装/构建）----------------
  log "构建/启动 web 前端（3000）"
  need_cmd node
  cd web
  if [ ! -d node_modules ]; then
    log "首次部署：安装前端依赖…"
    npm ci || npm install
  fi
  if [ ! -d dist ]; then
    log "首次部署：构建前端产物（npm run build）…"
    npm run build
  fi
  nohup npx vite preview --port 3000 --host > ../logs/web.log 2>&1 &
  echo $! > ../logs/web.pid
  cd "$ROOT"
  wait_for_http http://127.0.0.1:3000/ 60 && ok "web 前端 3000 已可访问（日志 logs/web.log）" \
    || { err "web 未起来，tail logs/web.log 排查"; exit 1; }

  echo
  ok "全部服务已启动"
  log "访问入口： http://<服务器IP>:3000   后端 API： http://<服务器IP>:8080/api"
  log "后台进程 PID： logs/{bff,agent,web}.pid —— 停止用 ./deploy.sh --stop"
}

# ---------------------------------------------------------------------------
# 停止
# ---------------------------------------------------------------------------
stop_proc() {
  local name="$1" pidfile="logs/$1.pid"
  if [ -f "$pidfile" ]; then
    local pid; pid="$(cat "$pidfile")"
    if kill -0 "$pid" 2>/dev/null; then kill "$pid" && ok "已停止 $name (pid $pid)"; fi
    rm -f "$pidfile"
  else
    warn "未找到 $name 的 pid 文件，跳过"
  fi
}

stop_all() {
  stop_proc bff; stop_proc agent; stop_proc web
  ok "后台进程已停止（中间件容器未动）"
}

down_all() {
  stop_proc bff; stop_proc agent; stop_proc web
  log "停止并移除中间件容器与数据卷…"
  docker compose down -v
  ok "已 docker compose down -v"
}

# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------
case "${1:-}" in
  --stop) stop_all ;;
  --down) down_all ;;
  ""|start) start_all ;;
  *) err "未知参数：$1（可用：无 / --stop / --down）"; exit 1 ;;
esac
