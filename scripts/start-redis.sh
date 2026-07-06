#!/bin/bash
# ============================================================
# 启动 Redis 容器（缓存热点数据 + 分布式 Session）
# 端口: 16379（宿主机）→ 6379（容器）
# 密码: 123123
# 版本: Redis 5.0（匹配 Spring Boot 2.0.5 / spring-boot-starter-data-redis）
# ============================================================

NETWORK=miaosha-net
REDIS_PASSWORD=123123

echo "=========================================="
echo "  启动 Redis"
echo "=========================================="

# 创建 Docker 网络（如果不存在）
docker network create --driver bridge $NETWORK 2>/dev/null || true

# 停止并删除旧容器
docker stop miaosha-redis 2>/dev/null
docker rm miaosha-redis 2>/dev/null

# 启动 Redis（带密码 + AOF 持久化）
docker run -d \
  --name miaosha-redis \
  --network $NETWORK \
  -p 16379:6379 \
  --restart always \
  --memory="64m" \
  redis:5.0-alpine \
  redis-server --requirepass ${REDIS_PASSWORD} --appendonly yes

echo ""
echo "Redis 启动完成！"
echo "  容器名: miaosha-redis"
echo "  端口:   16379"
echo "  密码:   123123"
echo "  网络:   ${NETWORK}"
echo ""
echo "连接测试: docker exec -it miaosha-redis redis-cli -a 123123 ping"
