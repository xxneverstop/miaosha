FROM eclipse-temurin:8-jre-alpine

# 时区通过 JVM 参数设置，无需 tzdata
# mkdir 是 busybox 内置，无需额外安装
RUN mkdir -p /home/xiong/miaosha /app/logs

WORKDIR /app
COPY miaosha.jar app.jar

EXPOSE 18091

ENV JAVA_OPTS="-Xms512m -Xmx512m -Djava.security.egd=file:/dev/./urandom -Duser.timezone=Asia/Shanghai"

ENTRYPOINT java ${JAVA_OPTS} \
    -jar app.jar \
    --spring.config.location=classpath:/application.yml,file:/home/xiong/miaosha/application.yml
