#!/bin/bash
# 启动应用实例 2：宿主机 18092 -> 容器 18091。
set -e

INSTANCE=2
APP_PORT=18092
NETWORK=miaosha-net
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# .env 的同名值优先；无 .env 时可通过 export 配置。
if [ -f "$PROJECT_ROOT/.env" ]; then
  set -a
  source "$PROJECT_ROOT/.env"
  set +a
fi
: "${MYSQL_PASSWORD:?请在根 .env 或环境变量中设置 MYSQL_PASSWORD}"
: "${REDIS_PASSWORD:?请在根 .env 或环境变量中设置 REDIS_PASSWORD}"
MYSQL_DATABASE=${MYSQL_DATABASE:-miaosha}
MYSQL_USERNAME=${MYSQL_USERNAME:-root}
JAVA_OPTS=${JAVA_OPTS:-"-Xms256m -Xmx256m -Djava.security.egd=file:/dev/./urandom -Duser.timezone=Asia/Shanghai"}
MQ_TOPICNAME=${MQ_TOPICNAME:-stock_topic}

cd "$PROJECT_ROOT"
if [ ! -f "miaosha.jar" ] || [ ! -f "application.yml" ]; then
  echo "错误: 根目录需要 miaosha.jar 和 application.yml。" >&2
  echo "请先打包，将 target/miaosha-1.0-SNAPSHOT.jar 复制为 miaosha.jar。" >&2
  exit 1
fi

echo "构建应用实例 ${INSTANCE}..."
docker network create --driver bridge "$NETWORK" 2>/dev/null || true
docker build -t miaosha-app:latest .
mkdir -p "$PROJECT_ROOT/logs2"
docker stop "miaosha-app-${INSTANCE}" 2>/dev/null || true
docker rm "miaosha-app-${INSTANCE}" 2>/dev/null || true

# 分服务容器之间固定走 Docker DNS/内部端口，避免混用本机 .env 的外部地址。
docker run -d \
  --name "miaosha-app-${INSTANCE}" \
  --network "$NETWORK" \
  -p "${APP_PORT}:18091" \
  -e "JAVA_OPTS=${JAVA_OPTS}" \
  -e "SPRING_DATASOURCE_URL=jdbc:mysql://miaosha-mysql:3306/${MYSQL_DATABASE}?useUnicode=true&characterEncoding=UTF-8&useSSL=false" \
  -e "SPRING_DATASOURCE_USERNAME=${MYSQL_USERNAME}" \
  -e "SPRING_DATASOURCE_PASSWORD=${MYSQL_PASSWORD}" \
  -e SPRING_REDIS_HOST=miaosha-redis \
  -e SPRING_REDIS_PORT=6379 \
  -e "SPRING_REDIS_PASSWORD=${REDIS_PASSWORD}" \
  -e MQ_NAMESERVER_ADDR=miaosha-rocketmq-namesrv:9876 \
  -e "MQ_TOPICNAME=${MQ_TOPICNAME}" \
  -v "$PROJECT_ROOT/application.yml:/home/xiong/miaosha/application.yml:ro" \
  -v "$PROJECT_ROOT/logs2:/app/logs" \
  --restart always \
  --memory="240m" \
  miaosha-app:latest

echo "后端实例 ${INSTANCE} 启动完成，端口 ${APP_PORT}。"
echo "双实例代理使用 nginx/nginx-scale.conf，调整 start-nginx.sh 的 CONF_FILE 后重启 Nginx。"
echo "查看日志: docker logs -f miaosha-app-${INSTANCE}"
