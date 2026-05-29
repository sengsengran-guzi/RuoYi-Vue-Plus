# sensenran-guzi 后端多阶段构建
# 用法（由 script/docker/docker-compose.deploy.yml 自动调用）：
#   docker compose -f script/docker/docker-compose.deploy.yml build
# 也可独立构建：
#   docker build -t sensenran-guzi/ruoyi-admin:prod .

# ============ Stage 1: builder ============
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 复制 pom 结构 + 源码（RuoYi-Vue-Plus 多模块，依赖关系复杂，全量复制后 mvn -am 自动拓扑）
COPY pom.xml .
COPY ruoyi-common ./ruoyi-common
COPY ruoyi-extend ./ruoyi-extend
COPY ruoyi-modules ./ruoyi-modules
COPY ruoyi-admin ./ruoyi-admin

# 用 BuildKit cache mount 缓存 maven 仓库，二次构建只下增量依赖
# 构建 ruoyi-admin + 其依赖的所有 ruoyi-common / ruoyi-modules（含 ruoyi-gz-*）
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -DskipTests -Dmaven.javadoc.skip=true \
        clean package -pl ruoyi-admin -am

# ============ Stage 2: runtime ============
FROM bellsoft/liberica-openjdk-rocky:17.0.16-cds

LABEL maintainer="Kevin <kui.wang.fe@gmail.com>" \
      project="sensenran-guzi"

# 运行时需要 curl 做 healthcheck（rocky 基础默认无 curl）
RUN microdnf install -y curl ca-certificates && microdnf clean all \
    && mkdir -p /app/logs /app/temp

WORKDIR /app

ENV SERVER_PORT=8080 \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    TZ=Asia/Shanghai \
    JAVA_OPTS=""

COPY --from=builder /build/ruoyi-admin/target/ruoyi-admin.jar /app/app.jar

EXPOSE ${SERVER_PORT}

# exec 形式让 java 接收 SIGTERM，容器停止才优雅
ENTRYPOINT ["sh", "-c", "exec java -Djava.security.egd=file:/dev/./urandom \
  -Dserver.port=${SERVER_PORT} \
  -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs/heapdump.hprof \
  -XX:+UseZGC ${JAVA_OPTS} \
  -jar /app/app.jar"]
