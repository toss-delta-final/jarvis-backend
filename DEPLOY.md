# 배포 가이드 — jarvis-backend

배포 담당이 이 repo만 보고 배포할 수 있도록 정리한 단일 문서다.
아키텍처 원본은 [docs/backend/03-architecture.md](docs/backend/03-architecture.md), 환경변수 원본은 [.env.example](.env.example).

## 0. 이 repo는 무엇이고, 어디에 배포하나

- **Spring Boot 3.5 / Java 21** 백엔드 API 서버. 프론트엔드는 별도 repo(`toss-delta-final/jarvis-frontend`), LLM은 별도 FastAPI 서비스.
- 배포 형상 두 가지 — **먼저 어느 쪽인지 확인**:
  - **A. FE 연동용 dev API 서버 1대** ← 이 문서 기본. 백엔드 컨테이너 + DB + Redis만.
  - **B. 운영 풀 형상** — nginx + next + spring **동일 오리진**, 상태(DB·Redis)는 RDS·ElastiCache로 외부화. 상세는 [docs/backend/03-architecture.md §1](docs/backend/03-architecture.md).

## 1. 빌드 & 실행 (컨테이너)

```bash
docker build -t jarvis-backend:dev .
docker run -p 8080:8080 --env-file deploy.env jarvis-backend:dev
```

- 멀티스테이지(gradle JDK21 빌드 → JRE21 런타임), non-root, 이미지 빌드 시 테스트 제외.
- 기본 프로파일 `dev` = base `application.yml`만 사용(local 프로파일 아님 → CORS 빈 비활성). 오버라이드는 `SPRING_PROFILES_ACTIVE`.
- 로컬에서 `docker build` + 컨테이너 부팅 검증 완료.

## 2. 환경변수 (`deploy.env`)

**필수 (기본값 없음 — 비면 부팅 실패):**

| 키 | 설명 |
|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | DB (MariaDB 11.4 권장 — RDS 등) |
| `REDIS_HOST` / `REDIS_PORT` | Redis (ElastiCache 등) |
| `JWT_SECRET` | AT/RT 서명(HS256) |
| `STREAM_TICKET_PRIVATE_KEY` / `STREAM_TICKET_KID` | 스트림 티켓 RS256 private key(base64 PKCS#8 DER) + 키 ID |
| `INTERNAL_TOKEN` | `/internal/**` 서비스 토큰 — **LLM(FastAPI)팀과 동일 값** |

**선택 (기본값 있음):** `APP_COOKIE_SECURE`(기본 `true`), `LLM_BASE_URL`(빈 값이면 FastAPI 통지 skip), `LLM_SSE_URL`.

전체 목록·용도는 [.env.example](.env.example).

## 3. ⚠️ 시크릿 — repo에 실제 값은 없다. 배포용은 새로 생성

repo에는 **키 목록(`.env.example`)만** 있고 실제 시크릿은 없다(커밋 금지). 로컬 개발용 값은 재사용하지 말고 **배포 환경용으로 새로 생성**한다:

```bash
# JWT 서명키 (HS256)
openssl rand -base64 48 | tr -d '\n'

# 스트림 티켓 RS256 private key — base64(PKCS#8 DER)
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  | openssl pkcs8 -topk8 -nocrypt -outform DER | base64 | tr -d '\n'
#   STREAM_TICKET_KID 는 임의 식별자, 예: jarvis-prod-2026-07

# internal 서비스 토큰 (LLM팀과 동일 값이어야 함)
openssl rand -hex 32
```

- `DB_*` / `REDIS_*`: 배포 인프라(RDS/ElastiCache 등) 접속값.
- `INTERNAL_TOKEN`: **LLM(FastAPI)팀과 같은 값으로 합의** (양쪽이 달라지면 `/internal` 콜백이 막힘).
- `LLM_BASE_URL`: LLM팀에게 실제 FastAPI 주소를 받아 설정(없으면 빈 값 = 세션종료 통지 skip, 부팅엔 지장 없음).
- 생성한 값은 repo 밖 안전 채널로만 공유(단톡·평문 금지). 배포 환경에선 GitHub Environment/Actions Secrets 등 시크릿 저장소 사용 권장.

## 4. DB 준비 (필수)

JPA는 `ddl-auto: validate` — **스키마를 만들지도 바꾸지도 않는다.** 배포 DB에 먼저 적용:

1. [docs/backend/schema.sql](docs/backend/schema.sql) — 스키마
2. [scripts/](scripts/) 시드 — `seed-phase1.sql` → `seed-phase2.sql` → `seed-phase6.sql` 순 (재실행 무해)

```bash
mariadb -h <host> -u <user> -p<pw> <db> < docs/backend/schema.sql
mariadb -h <host> -u <user> -p<pw> <db> < scripts/seed-phase1.sql   # 이후 phase2, phase6
```

## 5. 헬스체크

`GET /actuator/health` → `{"status":"UP"}`. ALB/오케스트레이터 헬스체크 타겟으로 사용.

## 6. 네트워킹 / CORS / 프론트엔드 연동

- 이 이미지에는 **CORS 설정이 없다**(운영 동일 오리진 철학, [03 §5](docs/backend/03-architecture.md)).
- **프론트엔드 개발자는 로컬에서 이 배포 API에 "Next rewrites 프록시"로 붙는다** → 브라우저 입장에서 동일 오리진이라 CORS도, 백엔드 변경도 필요 없다. **배포 담당은 공개 API URL만 FE팀에 주면 된다.**
  FE 쪽 설정(참고):
  ```js
  // jarvis-frontend / next.config.js
  async rewrites() {
    return [{ source: '/api/:path*', destination: `${process.env.API_PROXY_TARGET}/api/:path*` }];
  }
  ```
  ```bash
  # jarvis-frontend / .env.local
  # 배포 담당이 알려준 공개 API 주소. 노출 방식에 따라 형태가 다르다:
  #   커스텀 도메인      : https://dev-api.jarvis.shop
  #   ALB(로드밸런서)     : http://jarvis-dev-alb-1234567890.ap-northeast-2.elb.amazonaws.com
  #   EC2 퍼블릭 DNS+포트 : http://ec2-3-35-120-45.ap-northeast-2.compute.amazonaws.com:8080
  API_PROXY_TARGET=https://dev-api.jarvis.shop
  ```
- ⚠️ dev 서버를 공개 노출한다면 `/internal/**`는 서비스 토큰으로 보호되지만, 가능하면 인그레스에서 `/internal/**` 경로 자체를 차단 권장(운영은 nginx가 404 처리).

## 7. 배포 담당 체크리스트

- [ ] `docker build -t jarvis-backend .`
- [ ] `deploy.env` 작성 — **시크릿은 §3대로 새로 생성**, `INTERNAL_TOKEN`은 LLM팀과 합의, `LLM_BASE_URL`은 LLM팀에서 수령
- [ ] 배포 DB에 `docs/backend/schema.sql` + 시드(phase1·2·6) 적용
- [ ] 컨테이너 실행 후 `/actuator/health` = UP 확인
- [ ] FE팀에 **공개 API URL** 공유 (FE는 프록시 타깃으로 사용)
- [ ] (공개 노출 시) `/internal/**` 인그레스 차단
