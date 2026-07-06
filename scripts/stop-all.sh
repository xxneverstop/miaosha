#!/bin/bash
# ============================================================
# 停止并删除所有 miaosha 容器
# 数据卷保留（MySQL 数据不丢失）
# ============================================================

echo "=========================================="
echo "  停止所有容器"
echo "=========================================="

docker stop miaosha-nginx miaosha-app-1 miaosha-app-2 miaosha-mysql miaosha-redis miaosha-rocketmq-broker miaosha-rocketmq-namesrv 2>/dev/null
docker rm miaosha-nginx miaosha-app-1 miaosha-app-2 miaosha-mysql miaosha-redis miaosha-rocketmq-broker miaosha-rocketmq-namesrv 2>/dev/null

echo ""
echo "所有容器已停止并删除"
echo ""
echo "注意: MySQL 数据卷 (miaosha-mysql-data) 已保留"
echo "如需删除数据卷，执行: docker volume rm miaosha-mysql-data"
