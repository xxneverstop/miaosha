#!/bin/bash
# 启动 MySQL：宿主机 MYSQL_PORT -> 容器 3306；初始化仅创建 root。
set -e

NETWORK=miaosha-net
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# .env 的同名值优先；无 .env 时可通过 export 配置。
if [ -f "$PROJECT_ROOT/.env" ]; then
  set -a
  source "$PROJECT_ROOT/.env"
  set +a
fi
: "${MYSQL_PASSWORD:?请在根 .env 或环境变量中设置 MYSQL_PASSWORD}"
MYSQL_PORT=${MYSQL_PORT:-13306}
MYSQL_DATABASE=${MYSQL_DATABASE:-miaosha}

echo "启动 MySQL..."
docker network create --driver bridge "$NETWORK" 2>/dev/null || true
docker stop miaosha-mysql 2>/dev/null || true
docker rm miaosha-mysql 2>/dev/null || true

docker run -d \
  --name miaosha-mysql \
  --network "$NETWORK" \
  -p "${MYSQL_PORT}:3306" \
  -e "MYSQL_ROOT_PASSWORD=${MYSQL_PASSWORD}" \
  -e MYSQL_ROOT_HOST=% \
  -e "MYSQL_DATABASE=${MYSQL_DATABASE}" \
  -e TZ=Asia/Shanghai \
  -v "$PROJECT_ROOT/sql:/docker-entrypoint-initdb.d" \
  -v miaosha-mysql-data:/var/lib/mysql \
  --restart always \
  --memory="256m" \
  mysql:5.7 \
  --character-set-server=utf8mb4 \
  --collation-server=utf8mb4_unicode_ci \
  --default-time-zone=+08:00 \
  --sql-mode=NO_ENGINE_SUBSTITUTION

echo "等待 MySQL 就绪..."
until docker exec miaosha-mysql mysqladmin ping -h localhost --silent 2>/dev/null; do
  sleep 2
  echo -n "."
done
echo ""
echo "MySQL 启动完成，宿主机端口 ${MYSQL_PORT}，网络 ${NETWORK}。"
