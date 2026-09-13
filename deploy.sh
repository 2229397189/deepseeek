#!/usr/bin/env bash
# deploy.sh — Chiron Agent 一键部署（中间件 + 三服务 + 健康检查）
#
# 适用：Linux 服务器，已安装 docker / docker compose / JDK17 / Python3.11+
#       —— 若 bff/target/*.jar 不存在才需要 Maven；若 web/dist 不存在才需要 Node18+。
#       走「本地构建 + 上传」时这两个产物都在，服务器无需 Maven/Node。
# 用法：
#   ./deploy.sh               启动中间件容器 + BFF + agent-service + web，并逐个健康检查
#   ./deploy.sh --no-docker   不启容器，改连本机 PostgreSQL / Redis（拉不到镜像时用）
#   ./deploy.sh --stop        停止三个后台进程（不动中间件）
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

# 轮询「命令成功即视为就绪」的探针（如 pg_isready）
wait_ready() {
  local name="$1" timeout="$2"; shift 2
  local t=0
  until "$@" >/dev/null 2>&1; do
    sleep 2; t=$((t + 2))
    if [ "$t" -ge "$timeout" ]; then err "$name ${timeout}s 内未就绪"; return 1; fi
  done
  ok "$name 就绪"
  return 0
}

# 轮询「命令输出为 PONG」的探针（Redis）
wait_redis() {
  local name="$1" timeout="$2"; shift 2
  local t=0
  until [ "$("$@" 2>/dev/null)" = "PONG" ]; do
    sleep 2; t=$((t + 2))
    if [ "$t" -ge "$timeout" ]; then err "$name ${timeout}s 内未就绪"; return 1; fi
  done
  ok "$name 就绪"
  return 0
}

# ---------------------------------------------------------------------------
# 前置依赖检查
# ---------------------------------------------------------------------------
need_cmd() {
  command -v "$1" >/dev/null 2>&1 || { err "缺少命令：$1，请先安装"; exit 1; }
}
# --no-docker：不启动容器，改用本机已装好的 PostgreSQL / Redis。
# 适用「服务器出不了公网、拉不到 Docker Hub 镜像（如 pgvector/pgvector:pg16）」的场景。
NO_DOCKER=0
for _a in "$@"; do
  [ "$_a" = "--no-docker" ] && NO_DOCKER=1
done

need_cmd java
need_cmd curl
[ "$NO_DOCKER" = "1" ] || need_cmd docker

# Python 必须 >= 3.10：pydantic v2 会在运行时求值 `X | Y` 之类的新式标注，3.6/3.8 会直接报错。
# 系统自带 python3 常常偏老（如阿里云 Linux 3 是 3.6），所以这里挑一个够新的解释器；
# 也可用 PYTHON_BIN=python3.11 显式指定。
pick_python() {
  local c ver
  for c in python3.13 python3.12 python3.11 python3.10 python3; do
    command -v "$c" >/dev/null 2>&1 || continue
    ver="$("$c" -c 'import sys;print(sys.version_info[0]*100+sys.version_info[1])' 2>/dev/null)"
    if [ -n "$ver" ] && [ "$ver" -ge 310 ] 2>/dev/null; then echo "$c"; return 0; fi
  done
  return 1
}
PYTHON_BIN="${PYTHON_BIN:-$(pick_python)}"
if [ -z "${PYTHON_BIN:-}" ]; then
  err "未找到 Python >= 3.10。请安装（如 dnf install -y python3.11 python3.11-pip），或用 PYTHON_BIN=python3.11 指定。"
  exit 1
fi
ok "Python 解释器：$PYTHON_BIN（$("$PYTHON_BIN" --version 2>&1)）"

# 端口可配：同一台机器上若已有别的项目占用，就用环境变量错开。
#   BFF_PORT=8180 AGENT_PORT=8000 WEB_PORT=3100 ./deploy.sh --nginx
BFF_PORT="${BFF_PORT:-8080}"
AGENT_PORT="${AGENT_PORT:-8000}"
WEB_PORT="${WEB_PORT:-3000}"

# --nginx：前端交给 nginx 托管（同源 + /api 反代到 BFF），本脚本不再起静态服务器。
# 为什么需要它：前端 apiClient 用 `VITE_API_BASE ?? ''`，即**同源**设计；
# 若 web 和 BFF 分处不同端口，前端请求 /api 会打到静态服务器上拿不到 JSON。
NO_STATIC_WEB=0
for _a in "$@"; do
  [ "$_a" = "--nginx" ] && NO_STATIC_WEB=1
done

ok "端口规划：BFF=$BFF_PORT  agent=$AGENT_PORT  web=$WEB_PORT（nginx 托管=$NO_STATIC_WEB）"

# ---------------------------------------------------------------------------
# 启动
# ---------------------------------------------------------------------------
start_all() {
  # 1) 中间件 ------------------------------------------------------------------
  if [ "$NO_DOCKER" = "1" ]; then
    log "使用本机 PostgreSQL / Redis（--no-docker）"
    need_cmd pg_isready
    need_cmd redis-cli
    wait_ready "PostgreSQL" 90 pg_isready -h 127.0.0.1 -p 5432 -U lq -d lq_deepseek
    wait_redis "Redis" 60 redis-cli -h 127.0.0.1 ping
  else
    log "启动中间件容器（docker compose）"
    docker compose up -d
    wait_ready "PostgreSQL" 120 docker compose exec -T postgres pg_isready -U lq -d lq_deepseek
    wait_redis "Redis" 60 docker compose exec -T redis redis-cli ping
  fi

  # 2) BFF（Spring Boot / Maven，jar 在 bff/target/）---------------------------
  log "构建/启动 BFF（端口 $BFF_PORT）"
  local jar
  jar="$(find bff/target -maxdepth 1 -name '*.jar' 2>/dev/null | head -1)"
  if [ -z "$jar" ]; then
    need_cmd mvn
    log "未找到 bff/target/*.jar，先用 Maven 打包（跳过测试）…"
    mvn -f bff/pom.xml -q package -DskipTests
    jar="$(find bff/target -maxdepth 1 -name '*.jar' 2>/dev/null | head -1)"
  fi
  [ -n "$jar" ] || { err "BFF 打包失败，未生成 jar"; exit 1; }
  # 说明：迁移脚本会被打包进 fat jar，若 jar 是在「改动迁移脚本之前」构建的，jar 内那份就是旧的。
  # 只要源码目录还在，就用命令行参数把 Flyway 指向源码里最新的迁移脚本（命令行参数优先级最高）。
  local jar_args=""
  if [ -d bff/src/main/resources/db/migration ]; then
    # 注意：Flyway 9 的合法前缀是 filesystem: / classpath: / s3: / gcs:，**没有 file:**；
    # 写成 file: 会抛 "Unknown prefix for location" 导致启动失败。
    jar_args="--spring.flyway.locations=filesystem:$ROOT/bff/src/main/resources/db/migration"
  fi
  # 小内存实例（如 2GB 的 ECS）必须限制堆，否则 JVM 默认按物理内存取上限，容易把 PG 挤到 OOM。
  JAVA_OPTS="${JAVA_OPTS:--Xms128m -Xmx512m -XX:+UseSerialGC}"
  log "BFF JVM 参数：$JAVA_OPTS"
  # --server.port 覆盖端口；--lq.agent.base-url 跟着 AGENT_PORT 走（application.yml 里写死的是 8000）
  nohup java $JAVA_OPTS -jar "$jar" $jar_args \
    --server.port="$BFF_PORT" --lq.agent.base-url="http://127.0.0.1:$AGENT_PORT" \
    > logs/bff.log 2>&1 &
  echo $! > logs/bff.pid
  wait_for_port "$BFF_PORT" 180 && ok "BFF 端口 $BFF_PORT 已监听（日志 logs/bff.log）" \
    || { err "BFF 未起来，tail logs/bff.log 排查"; exit 1; }

  # 3) agent-service（FastAPI / Python venv）----------------------------------
  log "准备并启动 agent-service（端口 $AGENT_PORT）"
  cd agent-service
  if [ ! -d .venv ]; then
    log "创建 Python 虚拟环境并安装依赖…"
    "$PYTHON_BIN" -m venv .venv
    .venv/bin/pip install -q --upgrade pip
    .venv/bin/pip install -q -r requirements.txt
  fi
  # 默认离线确定性模式；若要接真实 LLM：export AGENT_FORCE_MOCK=false AGENT_LLM_API_KEY=sk-xxx
  local agent_mock="${AGENT_FORCE_MOCK:-true}"
  log "agent 模式：AGENT_FORCE_MOCK=$agent_mock"
  AGENT_FORCE_MOCK="$agent_mock" nohup .venv/bin/uvicorn app.main:app --host 127.0.0.1 --port "$AGENT_PORT" \
    > ../logs/agent.log 2>&1 &
  echo $! > ../logs/agent.pid
  cd "$ROOT"
  wait_for_http "http://127.0.0.1:$AGENT_PORT/health" 120 && ok "agent-service /health 正常（日志 logs/agent.log）" \
    || { err "agent-service 未起来，tail logs/agent.log 排查"; exit 1; }

  # 4) web（dist 缺失才用 npm 构建；托管交给 nginx 或本脚本的静态服务器）--------
  if [ ! -d web/dist ]; then
    log "未找到 web/dist，使用 npm 构建（需要 Node）…"
    need_cmd node
    cd web
    [ -d node_modules ] || npm ci || npm install
    npm run build
    cd "$ROOT"
  fi

  if [ "$NO_STATIC_WEB" = "1" ]; then
    ok "web 由 nginx 托管（--nginx），跳过静态服务器"
  else
    log "启动 web 静态服务器（端口 $WEB_PORT）"
    nohup "$PYTHON_BIN" serve_web.py web/dist "$WEB_PORT" > logs/web.log 2>&1 &
    echo $! > logs/web.pid
    wait_for_http "http://127.0.0.1:$WEB_PORT/" 60 && ok "web 前端 $WEB_PORT 已可访问（日志 logs/web.log）" \
      || { err "web 未起来，tail logs/web.log 排查"; exit 1; }
    warn "静态服务器不转发 /api；前端是「同源」设计，若 BFF 不同端口则 UI 调不到后端 —— 正式部署请用 --nginx。"
  fi

  echo
  ok "全部服务已启动"
  if [ "$NO_STATIC_WEB" = "1" ]; then
    log "对外入口由 nginx 提供；/api 反代到 127.0.0.1:$BFF_PORT"
  else
    log "访问入口： http://<服务器IP>:$WEB_PORT   后端 API： http://<服务器IP>:$BFF_PORT/api"
  fi
  log "后台进程 PID： logs/*.pid —— 停止用 ./deploy.sh --stop"
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
ACTION="start"
for _arg in "$@"; do
  case "$_arg" in
    --stop) ACTION="stop" ;;
    --down) ACTION="down" ;;
    --no-docker|--nginx|start) : ;;
    *) err "未知参数：$_arg（可用：start / --no-docker / --nginx / --stop / --down）"; exit 1 ;;
  esac
done
case "$ACTION" in
  start) start_all ;;
  stop)  stop_all ;;
  down)  down_all ;;
esac
