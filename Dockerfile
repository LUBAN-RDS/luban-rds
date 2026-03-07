# ==============================================================================
# Luban-RDS Dockerfile
# 多阶段构建，遵循行业最佳实践
# ==============================================================================

# ------------------------------------------------------------------------------
# Stage 1: 构建阶段
# 使用Maven镜像进行编译打包
# ------------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

LABEL stage="builder"

WORKDIR /build

COPY pom.xml .
COPY luban-rds-common/pom.xml luban-rds-common/
COPY luban-rds-core/pom.xml luban-rds-core/
COPY luban-rds-protocol/pom.xml luban-rds-protocol/
COPY luban-rds-server/pom.xml luban-rds-server/
COPY luban-rds-client/pom.xml luban-rds-client/
COPY luban-rds-persistence/pom.xml luban-rds-persistence/
COPY luban-rds-spring-boot-starter/pom.xml luban-rds-spring-boot-starter/
COPY luban-rds-bin/pom.xml luban-rds-bin/
COPY luban-rds-benchmark/pom.xml luban-rds-benchmark/

RUN mvn dependency:go-offline -B

COPY . .

RUN mvn clean package -DskipTests -B

RUN mv luban-rds-bin/target/luban-rds-jar-with-dependencies.jar /build/app.jar

# ------------------------------------------------------------------------------
# Stage 2: 运行时阶段
# 使用精简的JRE镜像，非root用户运行
# ------------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine AS runtime

LABEL maintainer="Luban-RDS Team"
LABEL version="1.0.0"
LABEL description="Luban-RDS - Lightweight Redis-compatible in-memory database"
LABEL org.opencontainers.image.source="https://github.com/your-org/luban-rds"
LABEL org.opencontainers.image.description="Luban-RDS - Lightweight Redis-compatible in-memory database"
LABEL org.opencontainers.image.licenses="Apache-2.0"

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:+UseStringDeduplication" \
    LUBAN_RDS_PORT=9736 \
    LUBAN_RDS_BIND=0.0.0.0 \
    LUBAN_RDS_DATA_DIR=/data \
    LUBAN_RDS_PERSIST_MODE=rdb \
    LUBAN_RDS_MAXMEMORY=0 \
    HEALTHCHECK_HOST=localhost \
    HEALTHCHECK_TIMEOUT=5

RUN apk add --no-cache \
    netcat-openbsd \
    tzdata \
    su-exec \
    && cp /usr/share/zoneinfo/${TZ} /etc/localtime \
    && echo "${TZ}" > /etc/timezone \
    && apk del tzdata

RUN addgroup -S -g 1000 luban \
    && adduser -S -u 1000 -G luban -h /app -s /sbin/nologin luban \
    && mkdir -p /data /logs /app/config \
    && chown -R luban:luban /data /logs /app

WORKDIR /app

COPY --from=builder --chown=luban:luban /build/app.jar /app/luban-rds.jar

COPY --chown=luban:luban docker/entrypoint.sh /app/entrypoint.sh
COPY --chown=luban:luban docker/healthcheck.sh /app/healthcheck.sh
RUN chmod +x /app/entrypoint.sh /app/healthcheck.sh

VOLUME ["/data", "/logs", "/app/config"]

EXPOSE 9736

HEALTHCHECK --interval=30s --timeout=10s --start-period=15s --retries=3 \
    CMD /app/healthcheck.sh

USER luban

ENTRYPOINT ["/app/entrypoint.sh"]

CMD ["sh", "-c", "java $JAVA_OPTS -jar /app/luban-rds.jar"]
