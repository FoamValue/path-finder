#!/usr/bin/env bash
# PathFinder 本地 Docker E2E 自动化测试
#
# 流程：
#   ① down 现有 path-finder 栈（数据卷保留，不动 pathfinder 库）
#   ② mvn package + 构建 server/nginx 镜像
#   ③ 启 mysql/redis，重置 pathfinder_test 测试库（隔离存储目录）
#   ④ 以 E2E 覆盖（验证码绕过 + 种子账号）启 server/nginx
#   ⑤ 等待 https://localhost 就绪
#   ⑥ 运行 Playwright E2E（本机 Chrome，channel=chrome）
#   EXIT 钩子自动恢复原部署栈（base+local+test override）
#
# 环境变量：
#   E2E_STORAGE_DIR    E2E 存储目录（默认 /Users/chenxinjie/logs/path-finder-e2e）
#   E2E_ADMIN_PASSWORD E2E 种子账号密码（默认 E2e@12345，与 compose e2e 默认一致）
#   SKIP_RESTORE=1     跑完不恢复原栈（用于调试）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
E2E_STORAGE="${E2E_STORAGE_DIR:-/Users/chenxinjie/logs/path-finder-e2e}"
ADMIN_PWD="${E2E_ADMIN_PASSWORD:-E2e@12345}"
MYSQL_ROOT_PWD="${MYSQL_ROOT_PASSWORD:-root123456}"

cd "$ROOT/docker"

ORIG=(docker compose -f docker-compose.yml -f docker-compose.local.yml -f docker-compose.test.yml)
E2E=(docker compose -f docker-compose.yml -f docker-compose.local.yml -f docker-compose.e2e.yml)

restore_orig() {
  if [ "${SKIP_RESTORE:-0}" = "1" ]; then
    echo "[e2e] SKIP_RESTORE=1：不恢复原栈"
    return
  fi
  cd "$ROOT/docker"
  echo "[e2e] 恢复原部署栈（base + local + test override）…"
  "${ORIG[@]}" up -d || echo "[e2e] 警告：恢复原栈失败，请手动 docker compose up -d"
}
trap restore_orig EXIT

echo "[e2e] ① 停用现有 path-finder 栈（数据卷保留）"
"${ORIG[@]}" down || true

echo "[e2e] ② 构建后端产物与镜像（server；nginx 静态包未改动复用既有镜像）"
( cd "$ROOT/server" && mvn -q -Dmaven.test.skip=true package )
STORAGE_HOST_DIR="$E2E_STORAGE" ADMIN_BOOTSTRAP_PASSWORD="$ADMIN_PWD" "${E2E[@]}" build server

echo "[e2e] ③ 启动中间件并重置 pathfinder_test"
STORAGE_HOST_DIR="$E2E_STORAGE" ADMIN_BOOTSTRAP_PASSWORD="$ADMIN_PWD" "${E2E[@]}" up -d mysql redis
mysql_ready=0
for _ in $(seq 1 90); do
  if "${E2E[@]}" exec -T mysql mysqladmin ping -h localhost -uroot -p"$MYSQL_ROOT_PWD" --silent >/dev/null 2>&1; then
    mysql_ready=1
    break
  fi
  sleep 2
done
if [ "$mysql_ready" != "1" ]; then
  echo "[e2e] MySQL 未就绪" >&2
  exit 1
fi
"${E2E[@]}" exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PWD" \
  -e "DROP DATABASE IF EXISTS pathfinder_test; CREATE DATABASE pathfinder_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; GRANT ALL PRIVILEGES ON pathfinder_test.* TO 'pathfinder'@'%'; FLUSH PRIVILEGES;"

echo "[e2e] ④ 启动 E2E server + nginx"
STORAGE_HOST_DIR="$E2E_STORAGE" ADMIN_BOOTSTRAP_PASSWORD="$ADMIN_PWD" "${E2E[@]}" up -d server nginx

echo "[e2e] ⑤ 等待服务就绪"
ready=0
for _ in $(seq 1 90); do
  code=$(curl -sk -o /dev/null -w '%{http_code}' https://localhost/api/publicKey || true)
  if [ "$code" = "200" ]; then
    ready=1
    break
  fi
  sleep 2
done
if [ "$ready" != "1" ]; then
  echo "[e2e] https://localhost 未就绪" >&2
  exit 1
fi

echo "[e2e] ⑥ 运行 Playwright E2E（本机 Chrome）"
cd "$ROOT/frontend"
E2E_BASE_URL="${E2E_BASE_URL:-https://localhost}" npm run test:e2e
