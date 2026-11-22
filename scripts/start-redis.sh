#!/bin/bash
# 启动 Redis：宿主机 REDIS_PORT -> 容器 6379，启用 AOF。
set -e

NETWORK=miaosha-net
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# .env 的同名值优先；无 .env 时可通过 export 配置。
if [ -f "$PROJECT_ROOT/.env" ]; then
  set -a
  source "$PROJECT_ROOT/.env"
  set +a
fi
: "${REDIS_PASSWORD:?请在根 .env 或环境变量中设置 REDIS_PASSWORD}"
REDIS_PORT=${REDIS_PORT:-16379}

echo "启动 Redis..."
docker network create --driver bridge "$NETWORK" 2>/dev/null || true
docker stop miaosha-redis 2>/dev/null || true
docker rm miaosha-redis 2>/dev/null || true

docker run -d \
  --name miaosha-redis \
  --network "$NETWORK" \
  -p "${REDIS_PORT}:6379" \
  --restart always \
  --memory="64m" \
  redis:5.0-alpine \
  redis-server --requirepass "${REDIS_PASSWORD}" --appendonly yes

echo "Redis 启动完成，宿主机端口 ${REDIS_PORT}，网络 ${NETWORK}。"
echo "连接测试: docker exec -it miaosha-redis redis-cli --askpass ping"
