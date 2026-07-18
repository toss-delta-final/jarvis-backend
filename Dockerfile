# syntax=docker/dockerfile:1
# JARVIS 백엔드 컨테이너 이미지 (dev 서버용). 상세 배포 안내는 DEPLOY.md 참조.

# --- build: gradle 래퍼로 bootJar 생성 (테스트 제외) ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# 래퍼/빌드 스크립트 먼저 복사해 의존성 레이어 캐시를 살린다
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
# gradlew가 CRLF로 커밋돼 있어도 리눅스에서 실행되도록 정규화 (.gitattributes로 이후엔 LF 고정)
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test
# bootJar만 추림 (Spring Boot가 함께 만드는 -plain.jar 제외)
RUN JAR="$(ls build/libs/*.jar | grep -v -- '-plain.jar' | head -1)" && cp "$JAR" /app/app.jar

# --- runtime: JRE만, non-root 실행 ---
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN groupadd -r app && useradd -r -g app app
COPY --from=build /app/app.jar app.jar
USER app

# 기본 프로파일 dev = base application.yml만 사용(local 프로파일 아님 → CORS 빈 비활성).
# 다른 프로파일이 필요하면 SPRING_PROFILES_ACTIVE로 오버라이드.
ENV SPRING_PROFILES_ACTIVE=dev
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
