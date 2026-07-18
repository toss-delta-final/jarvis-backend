# 배포 노트 — 공유 dev 백엔드 (컨테이너)

FE 연동용 **공유 dev 서버**를 컨테이너로 띄우기 위한 최소 안내다.
아키텍처 원본은 [docs/backend/03-architecture.md](docs/backend/03-architecture.md), 환경변수 원본은 [.env.example](.env.example).

> 운영(프로덕션) 형상은 nginx 뒤 동일 오리진 + RDS/ElastiCache 외부화(03 §1). 이 문서는 그와 별개로 **FE 연동용 단일 dev 인스턴스**를 빠르게 띄우는 용도다.

## 1. 빌드 & 실행

```bash
docker build -t jarvis-backend:dev .
docker run -p 8080:8080 --env-file dev.env jarvis-backend:dev
```

- 멀티스테이지: gradle(JDK 21)로 `bootJar` 빌드 → JRE 21 런타임, non-root 실행. 이미지 빌드 시 테스트는 제외(`-x test`).
- 기본 프로파일은 `dev` = **base `application.yml`만** 사용(local 프로파일이 아니라 CORS 빈이 뜨지 않음). 다른 프로파일이 필요하면 `SPRING_PROFILES_ACTIVE`로 오버라이드.

## 2. 환경변수 (`dev.env`)

**필수 (기본값 없음 — 비면 부팅 실패):**

| 키 | 설명 |
|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | dev DB (MariaDB 11.4 권장 — RDS 또는 컨테이너) |
| `REDIS_HOST` / `REDIS_PORT` | dev Redis |
| `JWT_SECRET` | AT/RT 서명(HS256), 256bit 이상 랜덤 |
| `STREAM_TICKET_PRIVATE_KEY` / `STREAM_TICKET_KID` | 스트림 티켓 RS256 — base64(PKCS#8 DER) private key + 키 ID |
| `INTERNAL_TOKEN` | `/internal/**` 서비스 토큰 — **LLM(FastAPI)팀과 동일 값** |

**선택 (기본값 있음):** `APP_COOKIE_SECURE`(기본 `true`), `LLM_BASE_URL`(빈 값이면 FastAPI 통지 skip), `LLM_SSE_URL`.

전체 목록·용도는 [.env.example](.env.example). 시크릿 실제 값은 repo 밖 안전 채널로 전달(커밋 금지).

## 3. DB 준비 (필수)

JPA는 `ddl-auto: validate` — **스키마를 만들지도 바꾸지도 않는다.** dev DB에 먼저 적용:

1. [docs/backend/schema.sql](docs/backend/schema.sql) — 스키마
2. [scripts/](scripts/) 시드 — `seed-phase1.sql` → `seed-phase2.sql` → `seed-phase6.sql` 순 (재실행 무해)

```bash
mariadb -h <host> -u <user> -p<pw> <db> < docs/backend/schema.sql
mariadb -h <host> -u <user> -p<pw> <db> < scripts/seed-phase1.sql
# ... phase2, phase6
```

## 4. 헬스체크

`GET /actuator/health` → `{"status":"UP"}`. ALB/오케스트레이터 헬스체크 타겟으로 사용.

## 5. CORS / 노출

- **FE 로컬 개발은 Next rewrites 프록시로 이 dev 서버에 붙는다** → 브라우저 입장에서 동일 오리진이라 **CORS 설정이 필요 없고 백엔드도 변경 없음**. FE는 프록시 타깃(`API_PROXY_TARGET=<dev URL>`)만 설정.
- 이 이미지에는 CORS 설정이 없다(운영 동일 오리진 철학, 03 §5). 프록시 없이 브라우저가 직접 cross-origin으로 붙어야 하는 구성이라면 별도 협의 필요(스펙 03 §5 갱신 후 `dev` 프로파일 CORS 추가).
- ⚠️ dev 서버를 공개 노출한다면 `/internal/**`는 서비스 토큰으로 보호되지만, 가능하면 인그레스에서 `/internal/**` 경로 자체를 차단 권장(운영은 nginx가 404 처리, 03 §4).
