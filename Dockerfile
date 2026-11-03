# 개발 ECS에서 실행할 Spring Boot 이미지를 만든다.
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY gradlew build.gradle settings.gradle ./
COPY gradle/ gradle/
COPY src/ src/
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon --console=plain

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN useradd --system --uid 10001 --home-dir /app app
COPY --from=build --chown=10001:10001 /app/build/libs/kitschcatch-backend-0.0.1-SNAPSHOT.jar /app/app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
