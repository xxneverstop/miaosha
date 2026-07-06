#!/bin/bash
# ============================================================
# 查看所有 miaosha 容器的状态和资源占用
# ============================================================

echo "=========================================="
echo "  容器状态"
echo "=========================================="
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" --filter "name=miaosha"

echo ""
echo "=========================================="
echo "  资源占用"
echo "=========================================="
docker stats --no-stream miaosha-nginx miaosha-app-1 miaosha-app-2 miaosha-mysql miaosha-redis miaosha-rocketmq-namesrv miaosha-rocketmq-broker 2>/dev/null
