# Build the Spring Boot executable jar with Java 21.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY .mvn/cloud-settings.xml ./.mvn/cloud-settings.xml
COPY pom.xml ./
RUN mvn -B -ntp -U -s .mvn/cloud-settings.xml dependency:go-offline
COPY src ./src
# Run offline service/controller tests; the full context test needs MySQL, API keys and MCP.
RUN mvn -B -ntp -U -s .mvn/cloud-settings.xml \
    -Dtest=ChatMessagesServiceImplTests,ChatControllerTests package

# Install the MCP server at build time to avoid downloads during startup.
FROM node:22-bookworm-slim AS mcp
ARG AMAP_MCP_VERSION=0.0.8
RUN npm install --prefix /opt/amap --omit=dev --no-audit --no-fund \
    @amap/amap-maps-mcp-server@${AMAP_MCP_VERSION}

FROM eclipse-temurin:21-jre-noble AS runtime
WORKDIR /app
# MySQL connection settings and API keys are supplied at runtime (see .env.example).
ENV TZ=Asia/Shanghai \
    SPRING_PROFILES_ACTIVE=cloud \
    SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8888

COPY --from=mcp /usr/local/bin/node /usr/local/bin/node
COPY --from=mcp /opt/amap /opt/amap
RUN node --version \
    && groupadd --system app \
    && useradd --system --gid app --create-home app

COPY --from=build --chown=app:app /build/target/*.jar /app/app.jar
USER app

# Set the WeChat Cloud Hosting service port to the same value.
EXPOSE 8888
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
