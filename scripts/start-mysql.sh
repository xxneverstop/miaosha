#!/bin/bash
# ============================================================
# 启动 MySQL 容器
# 端口: 13306（宿主机）→ 3306（容器）
# ============================================================

MYSQL_PORT=13306
MYSQL_ROOT_PASSWORD=123123
MYSQL_DATABASE=miaosha
NETWORK=miaosha-net
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "=========================================="
echo "  启动 MySQL"
echo "=========================================="

# 创建 Docker 网络（如果不存在）
docker network create --driver bridge $NETWORK 2>/dev/null || true

# 停止并删除旧容器
docker stop miaosha-mysql 2>/dev/null
docker rm miaosha-mysql 2>/dev/null

# 启动 MySQL
docker run -d \
  --name miaosha-mysql \
  --network $NETWORK \
  -p ${MYSQL_PORT}:3306 \
  -e MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD} \
  -e MYSQL_ROOT_HOST=% \
  -e MYSQL_DATABASE=${MYSQL_DATABASE} \
  -e TZ=Asia/Shanghai \
  -v ${PROJECT_ROOT}/sql:/docker-entrypoint-initdb.d \
  -v miaosha-mysql-data:/var/lib/mysql \
  --restart always \
  --memory="256m" \
  mysql:5.7 \
  --character-set-server=utf8mb4 \
  --collation-server=utf8mb4_unicode_ci \
  --default-time-zone=+08:00 \
  --sql-mode=NO_ENGINE_SUBSTITUTION

echo ""
echo "等待 MySQL 就绪..."
until docker exec miaosha-mysql mysqladmin ping -h localhost --silent 2>/dev/null; do
  sleep 2
  echo -n "."
done

echo ""
echo "MySQL 启动完成！"
echo "  容器名: miaosha-mysql"
echo "  端口:   ${MYSQL_PORT}"
echo "  网络:   ${NETWORK}"
