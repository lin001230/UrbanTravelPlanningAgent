# Build the Spring Boot executable jar with Java 21.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY .mvn/cloud-settings.xml ./.mvn/cloud-settings.xml
COPY pom.xml ./
RUN mvn -B -ntp -U -s .mvn/cloud-settings.xml dependency:go-offline
COPY src ./src
# Tests stay on the developer machine. Knowledge vectors are imported into MySQL, not packaged here.
RUN mvn -B -ntp -U -s .mvn/cloud-settings.xml -DskipTests package

FROM eclipse-temurin:21-jre-noble AS runtime
WORKDIR /app
# MySQL connection settings and API keys are supplied at runtime (see .env.example).
ENV TZ=Asia/Shanghai \
    SPRING_PROFILES_ACTIVE=cloud \
    SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8888

RUN groupadd --system app \
    && useradd --system --gid app --create-home app

COPY --from=build --chown=app:app /build/target/*.jar /app/app.jar
USER app

# Cloud hosting service port must match. HTTP health check path is /api/health.
EXPOSE 8888
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
