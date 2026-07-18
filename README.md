# jarvis-backend

Spring Boot 3.5 (Java 21) — 명세는 [docs/backend/](docs/backend/README.md)가 원본이다 (01→05 읽고 구현).

**배포하려면 → [DEPLOY.md](DEPLOY.md) 부터 읽으세요.**

## 로컬 실행 (3단계)

```bash
# 1. 개발 환경 셋업 — .env/application-local.yml 생성 + MariaDB·Redis 컨테이너 기동
bash scripts/setup-local.sh

# 2. 빌드/실행 (JDK·Gradle이 PATH에 없으므로 JAVA_HOME 명시 — Windows Git Bash 기준)
JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.11.10-hotspot" ./gradlew bootRun

# 3. 확인
curl http://localhost:8080/actuator/health   # {"status":"UP"}
```

PowerShell에서는:

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot"; .\gradlew.bat bootRun
```

## 배포 담당에게

- **배포 방법 전체 → [DEPLOY.md](DEPLOY.md)** (빌드·실행·시크릿 생성·DB 시드·헬스체크·프론트 연동)
- 필요한 환경변수 전체 목록: [.env.example](.env.example) (원본 규약: [03 §5](docs/backend/03-architecture.md))
- `docker-compose.yml`은 **로컬 개발용** — 배포 DB는 RDS(MariaDB)로 외부화됨(03 §1-2). RDS에는 `DB_URL/DB_USERNAME/DB_PASSWORD`만 실제 값으로 주입하면 된다.
- MariaDB 이미지는 11.4 — RDS 버전과 어긋나면 맞춰서 조정.
- 빌드 산출물: `./gradlew bootJar` → `build/libs/jarvis-backend-*.jar`
- 프로파일: 기본 `local`(application-local.yml). 배포 시 `SPRING_PROFILES_ACTIVE`로 별도 프로파일 지정 + 환경변수 주입.

## 규약 요약

- 응답은 전부 `ApiResponse` envelope (03 D2). 에러 코드 목록: 04 §11.
- 패키지: 도메인 우선(package-by-feature), 공통은 `global/` (03 §3).
- 시크릿 커밋 금지 — `.env`, `application-local.yml`은 gitignore.
