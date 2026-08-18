# ─── Build stage ────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# Gradle wrapper와 빌드 메타 먼저 복사해 의존성 캐시 활용
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --version --no-daemon

# 소스 복사 후 bootJar 빌드 (테스트 제외)
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# ─── Runtime stage ──────────────────────────────────────────────
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
