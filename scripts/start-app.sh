#!/bin/bash
# ============================================================
# 启动后端服务容器（实例 1）
# 端口: 18091（宿主机）→ 18091（容器）
# ============================================================

INSTANCE=1
APP_PORT=18091
MYSQL_ROOT_PASSWORD=123123
MYSQL_DATABASE=miaosha
JAVA_OPTS="-Xms192m -Xmx192m -Djava.security.egd=file:/dev/./urandom -Duser.timezone=Asia/Shanghai"
NETWORK=miaosha-net
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "=========================================="
echo "  启动后端服务 (实例 ${INSTANCE})"
echo "=========================================="

# 创建 Docker 网络（如果不存在）
docker network create --driver bridge $NETWORK 2>/dev/null || true

# ============ 构建镜像 ============
cd "$PROJECT_ROOT"

if [ ! -f "miaosha.jar" ]; then
  echo ""
  echo "错误: miaosha.jar 不存在！"
  echo "请先执行 mvn package，然后将 target/miaosha-*.jar 复制为 miaosha.jar"
  exit 1
fi

echo "构建 Docker 镜像..."
docker build -t miaosha-app:latest .
echo "镜像构建完成"

# ============ 启动容器 ============
mkdir -p "$PROJECT_ROOT/logs"

docker stop miaosha-app-${INSTANCE} 2>/dev/null
docker rm miaosha-app-${INSTANCE} 2>/dev/null

docker run -d \
  --name miaosha-app-${INSTANCE} \
  --network $NETWORK \
  -p ${APP_PORT}:18091 \
  -e JAVA_OPTS="${JAVA_OPTS}" \
  -e SPRING_DATASOURCE_URL="jdbc:mysql://miaosha-mysql:3306/${MYSQL_DATABASE}?useUnicode=true&characterEncoding=UTF-8&useSSL=false" \
  -e SPRING_DATASOURCE_USERNAME="root" \
  -e SPRING_DATASOURCE_PASSWORD="${MYSQL_ROOT_PASSWORD}" \
  -e SPRING_REDIS_HOST=miaosha-redis \
  -e SPRING_REDIS_PORT=6379 \
  -e SPRING_REDIS_PASSWORD=123123 \
  -e MQ_NAMESERVER_ADDR=miaosha-rocketmq-namesrv:9876 \
  -e MQ_TOPICNAME=stock_topic \
  -v ${PROJECT_ROOT}/application.yml:/home/xiong/miaosha/application.yml:ro \
  -v ${PROJECT_ROOT}/logs:/app/logs \
  --restart always \
  --memory="320m" \
  miaosha-app:latest

echo ""
echo "后端实例 ${INSTANCE} 启动完成！"
echo "  容器名: miaosha-app-${INSTANCE}"
echo "  端口:   ${APP_PORT}"
echo "  网络:   ${NETWORK}"
echo ""
echo "查看日志: docker logs -f miaosha-app-${INSTANCE}"
