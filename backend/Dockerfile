# 1단계: Gradle로 bootJar 빌드 (테스트는 Docker가 필요하므로 제외)
FROM --platform=$BUILDPLATFORM gradle:8.10.2-jdk17 AS build
WORKDIR /app
COPY build.gradle settings.gradle ./
COPY src ./src
RUN gradle bootJar -x test --no-daemon

# 2단계: JRE만 담은 실행 이미지
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
RUN mkdir -p /data
# POI가 큰 워크북을 통째로 메모리에 올리므로 기본(25%)보다 넉넉하게
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
