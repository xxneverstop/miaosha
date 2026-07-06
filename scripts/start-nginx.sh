#!/bin/bash
# ============================================================
# 启动 Nginx 容器
# 端口: 81（宿主机）→ 80（容器）
# ============================================================

NETWORK=miaosha-net
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONF_FILE="nginx.conf"  # 单后端用 nginx.conf，双后端改为 nginx-scale.conf

echo "=========================================="
echo "  启动 Nginx"
echo "=========================================="

# 创建 Docker 网络（如果不存在）
docker network create --driver bridge $NETWORK 2>/dev/null || true

# ============ 自动设置 gethost.js ============
echo "var g_host = window.location.host;" > ${PROJECT_ROOT}/html/gethost.js
echo "已设置 gethost.js: var g_host = window.location.host;"

# ============ 启动 Nginx ============
docker stop miaosha-nginx 2>/dev/null
docker rm miaosha-nginx 2>/dev/null

docker run -d \
  --name miaosha-nginx \
  --network $NETWORK \
  -p 81:80 \
  -v ${PROJECT_ROOT}/html:/usr/share/nginx/html:ro \
  -v ${PROJECT_ROOT}/nginx/${CONF_FILE}:/etc/nginx/conf.d/default.conf:ro \
  --restart always \
  --memory="48m" \
  nginx:alpine

echo ""
echo "Nginx 启动完成！"
echo "  容器名: miaosha-nginx"
echo "  端口:   80"
echo "  配置:   nginx/${CONF_FILE}"
echo ""
echo "访问: http://100.91.90.71/login.html"
