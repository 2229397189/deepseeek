#!/usr/bin/env bash
# svc-local.sh — 无 Docker 环境下三个应用服务的起停与状态
#   BFF(8080) / agent-service(8000) / web(3000)
#
# 用法： ./svc-local.sh start | stop | restart | status
#
# 为什么需要它：这三个服务是 nohup 起的裸进程，**服务器重启后不会自动恢复**
# （中间件 postgresql / redis 由 systemd 管理会自动起，应用服务不会）。
# 重启机器后执行 `./svc-local.sh start` 即可恢复。

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")" || exit 1
mkdir -p logs

PY="${PYTHON_BIN:-python3.11}"
JO="${JAVA_OPTS:--Xms128m -Xmx512m -XX:+UseSerialGC}"

start() {
  for n in bff agent web; do
    p="$(cat "logs/$n.pid" 2>/dev/null || true)"
    if [ -n "$p" ] && kill -0 "$p" 2>/dev/null; then
      echo "$n 已在运行 (pid $p)"; continue
    fi
    case "$n" in
      bff)
        nohup java $JO -jar $(ls bff/target/*.jar | head -1) \
          --spring.flyway.locations=filesystem:$PWD/bff/src/main/resources/db/migration \
          > logs/bff.log 2>&1 &
        ;;
      agent)
        AGENT_FORCE_MOCK="${AGENT_FORCE_MOCK:-true}" nohup agent-service/.venv/bin/uvicorn \
          app.main:app --app-dir agent-service --host 127.0.0.1 --port 8000 \
          > logs/agent.log 2>&1 &
        ;;
      web)
        nohup "$PY" serve_web.py web/dist 3000 > logs/web.log 2>&1 &
        ;;
    esac
    echo $! > "logs/$n.pid"
    echo "$n 已启动 (pid $!)"
  done
}

stop() {
  for n in bff agent web; do
    p="$(cat "logs/$n.pid" 2>/dev/null || true)"
    if [ -n "$p" ] && kill "$p" 2>/dev/null; then echo "$n 已停止 (pid $p)"; else echo "$n 未运行"; fi
    rm -f "logs/$n.pid"
  done
}

status() {
  for n in bff agent web; do
    p="$(cat "logs/$n.pid" 2>/dev/null || true)"
    if [ -n "$p" ] && kill -0 "$p" 2>/dev/null; then echo "$n: 运行中 (pid $p)"; else echo "$n: 已停止"; fi
  done
  echo "--- 健康检查 ---"
  echo "health: $(curl -s -m 5 http://127.0.0.1:8000/health || echo 无响应)"
  echo "web:    $(curl -s -m 5 -o /dev/null -w '%{http_code}' http://127.0.0.1:3000/ || echo 无响应)"
  echo "api:    $(curl -s -m 5 -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/swagger-ui.html || echo 无响应)"
  echo "--- 中间件 ---"
  if pg_isready -h 127.0.0.1 -q; then echo "postgresql: 就绪"; else echo "postgresql: 未就绪"; fi
  echo "redis:      $(redis-cli -h 127.0.0.1 ping 2>/dev/null || echo 无响应)"
}

case "${1:-status}" in
  start)   start ;;
  stop)    stop ;;
  restart) stop; sleep 2; start ;;
  status)  status ;;
  *) echo "用法: $0 start|stop|restart|status"; exit 1 ;;
esac
