# z-llm-agent-proxy 镜像（服务形态：fat jar 单进程）
#
# 前置：mvn -B -DskipTests package
# 构建：docker build -f deploy/Dockerfile -t ghcr.io/yuku123/z-llm-agent-proxy:1.0.0 ..

FROM eclipse-temurin:17-jre-jammy

ARG JAR_FILE=target/z-llm-agent-proxy.jar
ENV JAVA_OPTS="-Xms256m -Xmx512m"

WORKDIR /app
COPY ${JAR_FILE} /app/app.jar

EXPOSE 9098

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

LABEL org.opencontainers.image.title="z-llm-agent-proxy" \
      org.opencontainers.image.description="把本机 CLI agent（Claude Code / Qoder / Hermes / Opencode / Minimax）暴露成 OpenAI 兼容 streaming chat API，并向 Nacos 注册" \
      org.opencontainers.image.source="https://github.com/z-opc-foundation/z-llm-agent-proxy" \
      org.opencontainers.image.licenses="MIT"
