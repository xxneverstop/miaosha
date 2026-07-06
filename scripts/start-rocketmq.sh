#!/bin/bash
# ============================================================
# 启动 RocketMQ 容器（NameServer + Broker + 自动创建 Topic）
# 版本: 4.9.4（兼容客户端 rocketmq-client 4.3.0）
# NameServer 端口: 19876（宿主机）→ 9876（容器）
# Broker 端口:     10911（宿主机）→ 10911（容器）
#
# 用法:
#   bash scripts/start-rocketmq.sh 182.92.116.131   # 服务器部署
#   bash scripts/start-rocketmq.sh                  # 纯本机（用本地 IP）
# ============================================================

NETWORK=miaosha-net
NAMESRV_CONTAINER=miaosha-rocketmq-namesrv
BROKER_CONTAINER=miaosha-rocketmq-broker
NAMESRV_PORT=19876
BROKER_PORT=10911
ROCKETMQ_VERSION=4.9.4
TOPIC_NAME=stock_topic

# 服务器公网 IP（第一个参数传入，或自动检测本机 IP）
if [ -n "$1" ]; then
  SERVER_IP="$1"
else
  SERVER_IP=$(hostname -I 2>/dev/null | awk '{print $1}')
fi
echo "服务器 IP: ${SERVER_IP}"

echo "=========================================="
echo "  启动 RocketMQ"
echo "=========================================="

# 创建 Docker 网络（如果不存在）
docker network create --driver bridge $NETWORK 2>/dev/null || true

# ============ 启动 NameServer ============
docker stop $NAMESRV_CONTAINER 2>/dev/null
docker rm $NAMESRV_CONTAINER 2>/dev/null

echo "启动 NameServer..."
docker run -d \
  --name $NAMESRV_CONTAINER \
  --network $NETWORK \
  -p ${NAMESRV_PORT}:9876 \
  -e "JAVA_OPT_EXT=-Xms80m -Xmx80m -Xmn32m" \
  --restart always \
  --memory="160m" \
  apache/rocketmq:${ROCKETMQ_VERSION} \
  sh mqnamesrv

# ============ 启动 Broker ============
docker stop $BROKER_CONTAINER 2>/dev/null
docker rm $BROKER_CONTAINER 2>/dev/null

# 生成 broker.conf
BROKER_CONF_DIR="${HOME}/.miaosha"
mkdir -p "$BROKER_CONF_DIR"
BROKER_CONF_FILE="${BROKER_CONF_DIR}/broker.conf"

cat > "$BROKER_CONF_FILE" << CFGEOF
brokerClusterName = DefaultCluster
brokerName = broker-a
brokerId = 0
deleteWhen = 04
fileReservedTime = 48
brokerRole = ASYNC_MASTER
flushDiskType = ASYNC_FLUSH
listenPort = 10911
brokerIP1 = ${SERVER_IP}
autoCreateTopicEnable = true
CFGEOF

echo "启动 Broker..."
docker run -d \
  --name $BROKER_CONTAINER \
  --network $NETWORK \
  -p ${BROKER_PORT}:10911 \
  -e "NAMESRV_ADDR=${NAMESRV_CONTAINER}:9876" \
  -e "JAVA_OPT_EXT=-Xms128m -Xmx128m -Xmn64m -XX:MaxDirectMemorySize=64m" \
  -v "${BROKER_CONF_FILE}":/home/rocketmq/rocketmq-${ROCKETMQ_VERSION}/conf/broker.conf:ro \
  --restart always \
  --memory="384m" \
  apache/rocketmq:${ROCKETMQ_VERSION} \
  sh mqbroker -n ${NAMESRV_CONTAINER}:9876 \
    -c /home/rocketmq/rocketmq-${ROCKETMQ_VERSION}/conf/broker.conf

# ============ 等待 Broker 就绪 ============
echo "等待 Broker 就绪..."
for i in $(seq 1 15); do
  if docker logs $BROKER_CONTAINER 2>/dev/null | grep -q "boot success"; then
    break
  fi
  sleep 2
done

# ============ 自动创建 Topic（直连 Broker 内网 IP，绕过公网回连问题）============
BROKER_DOCKER_IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $BROKER_CONTAINER)
echo "Broker 内网 IP: ${BROKER_DOCKER_IP}"

docker exec $BROKER_CONTAINER sh mqadmin updateTopic \
  -b ${BROKER_DOCKER_IP}:10911 \
  -t ${TOPIC_NAME} -r 4 -w 4 2>/dev/null

# 确认创建成功
if docker exec $BROKER_CONTAINER sh mqadmin topicList \
    -b ${BROKER_DOCKER_IP}:10911 2>/dev/null | grep -q "${TOPIC_NAME}"; then
  echo "Topic [${TOPIC_NAME}] 创建成功"
else
  echo "警告: Topic 创建失败，请手动执行："
  echo "  docker exec ${BROKER_CONTAINER} sh mqadmin updateTopic -b ${BROKER_DOCKER_IP}:10911 -t ${TOPIC_NAME} -r 4 -w 4"
fi

echo ""
echo "=========================================="
echo "  RocketMQ 启动完成"
echo "=========================================="
echo "  NameServer:  ${SERVER_IP}:${NAMESRV_PORT}"
echo "  Broker:      ${SERVER_IP}:${BROKER_PORT}"
echo "  Topic:       ${TOPIC_NAME}"
echo ""
echo "  本地开发:  mq.nameserver.addr = ${SERVER_IP}:${NAMESRV_PORT}"
echo "  服务器部署: mq.nameserver.addr = ${NAMESRV_CONTAINER}:9876"
