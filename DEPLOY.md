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
| `INTERNAL_API_TOKEN` | `/internal/**` 서비스 토큰 — **LLM(FastAPI)팀과 동일 값** (org 공유 시크릿) |
| `CUSTOMER_LABEL_SECRET` | I-14 고객 라벨(사례번호) HMAC 키 — **우리 서버 전용**(공유 금지). 값이 바뀌면 과거 사례번호와 이어지지 않는다. `openssl rand -base64 48` |

**선택 (기본값 있음):** `APP_COOKIE_SECURE`(기본 `true`), `LLM_BASE_URL`·`LLM_SSE_URL`(FastAPI 공개주소 — 보통 동일 값, 빈 값이면 통지 skip·채팅 degrade. 배포는 GitHub Variable `FASTAPI_BASE_URL`을 두 이름으로 주입).

### 2-1. ⚠️ Kafka — dev 서버는 **접두어를 반드시 준다**

`KAFKA_BOOTSTRAP_SERVERS`(기본 `localhost:9092`)와 `APP_KAFKA_PREFIX`(기본 빈 값) 두 개다.

브로커는 **1대뿐이고**(08 D6) 이 dev 서버는 운영 4대와 **같은 이미지**를 돌린다. 그래서 dev가 운영
브로커(`172.31.46.48:9092`)를 보게 설정하면서 접두어를 비워두면, dev 컨테이너의 컨슈머가 **운영 컨슈머
그룹의 정식 멤버가 된다.** 카프카는 한 그룹 안에서 파티션을 나눠주므로 **운영 이벤트의 일부가 dev DB에
적재되고 오프셋까지 커밋된다** — 운영 DB는 그 이벤트를 영영 못 받는다. **에러도 lag도 남지 않는다.**

| 배포 대상 | `KAFKA_BOOTSTRAP_SERVERS` | `APP_KAFKA_PREFIX` |
|---|---|---|
| 운영 4대 (deploy.yml) | `172.31.46.48:9092` | **빈 값** — 기존 토픽·오프셋 유지 |
| dev 서버 (이 문서) | `172.31.46.48:9092` (같은 브로커를 공유한다) | **`dev-`** |

- **접두어가 유일한 방어선이다.** dev는 직렬화 계약·컨슈머 로직·ZSET 집계를 실제로 검증하는 곳이라
  브로커에 붙어 있어야 하고, 보안그룹으로 막는 안은 그 목적과 충돌해 기각했다(08 D10).
  참고로 `jarvis-backend-sg-v2`에는 9092뿐 아니라 RDS 3306·Redis 6379·ALB 80이 함께 묶여 있어
  여기서 빼면 dev가 아예 뜨지 못한다.
- **토픽은 미리 만들지 않아도 된다.** 브로커의 `auto.create.topics.enable=false`가 막는 것은 암묵적
  생성이고, 앱은 기동 시 `KafkaAdmin`이 명시적으로 만든다. 수동 생성한다면 이름은
  `dev-behavior-events` / `dev-behavior-events-dlt`(소문자 `-dlt`)이고 **둘 다 파티션 3 · RF 1**이어야
  한다 — DLT가 1파티션이면 2번 파티션의 실패 레코드를 옮기다 그것마저 실패한다.
- 확인 ①: 기동 로그에 해석된 이름이 한 줄 찍힌다 —
  `행동 이벤트 스트림 (08 D10) — topic=dev-behavior-events, ...`. 여기에 `dev-`가 없으면 잘못 붙은 것이다.
- 확인 ②: `PREFIX=dev- bash scripts/verify-kafka-pipeline.sh`
- 확인 ③: dev 배포 직후 운영 그룹 멤버가 여전히 4개인지 —
  `kafka-consumer-groups.sh --describe --group persister --members` (2026-08-12 기준 4개·전부 운영 IP).

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
- `INTERNAL_API_TOKEN`: **LLM(FastAPI)팀과 같은 값으로 합의** (양쪽이 달라지면 `/internal` 콜백이 막힘). org 공유 시크릿으로 두면 한 곳만 관리.
- `LLM_BASE_URL`·`LLM_SSE_URL`: LLM팀에게 실제 FastAPI 공개 주소를 받아 설정(보통 동일 값). 없으면 빈 값 = 통지 skip·채팅 degrade, 부팅엔 지장 없음. (배포 파이프라인은 GitHub Variable `FASTAPI_BASE_URL` 하나를 이 두 이름으로 주입)
- 생성한 값은 repo 밖 안전 채널로만 공유(단톡·평문 금지). 배포 환경에선 GitHub Environment/Actions Secrets 등 시크릿 저장소 사용 권장.

## 4. DB 준비 (필수)

JPA는 `ddl-auto: validate` — **스키마를 만들지도 바꾸지도 않는다.** 엔티티가 기대하는 테이블·컬럼이
DB에 없으면 앱이 **기동 자체를 거부**하므로, DB 반영이 항상 앱 배포보다 먼저다.

### 4-1. 신규 DB (처음 구축)

1. [docs/backend/schema.sql](docs/backend/schema.sql) — 스키마 전체 (최초 1회 전용 — 재실행 불가 DDL)
2. [scripts/](scripts/) 시드 — `seed-accounts.sql` → `seed-catalog.sql` → `seed-commerce-demo.sql` → `seed-analytics-demo.sql` 순 (재실행 무해)

```bash
mariadb -h <host> -u <user> -p<pw> <db> < docs/backend/schema.sql
mariadb -h <host> -u <user> -p<pw> <db> < scripts/seed-accounts.sql   # 이후 catalog, commerce-demo, analytics-demo 순
```

### 4-2. 이미 운영 중인 DB (스키마가 변경됐을 때)

`schema.sql`을 다시 흘리면 "table already exists"로 중단된다 — 대신 **증분 마이그레이션**을 적용한다.
`scripts/migrate-*.sql`을 **날짜 접두사 오름차순**으로 전부 실행하면 된다(전부 재실행 무해 —
이미 적용된 것은 조용히 건너뛰므로 어디까지 적용했는지 기억할 필요 없음). 쌓인 데이터는 보존된다.

```bash
for f in scripts/migrate-*.sql; do
  mariadb -h <host> -u <user> -p<pw> <db> < "$f"
done
```

**⚠️ 적용 시점은 마이그레이션마다 다르다** — 아래 표의 「적용 시점」을 반드시 확인한다.
대부분은 **마이그레이션 → 앱 재기동** 순이지만, 제약을 조이는(NOT NULL 등) 마이그레이션은
**새 앱이 뜬 뒤**에 적용해야 한다. 구 버전 앱이 조여진 스키마에 쓰면 그 데이터가 유실된다.

| 마이그레이션 | 내용 | 적용 시점 |
|---|---|---|
| `migrate-2026-07-30-recommendation-list.sql` | 추천 목록 영구 사본 테이블 2개 신설 + `behavior_events` 컬럼 5개(전부 NULL 허용)·인덱스 2개 | **앱 배포 전.** 새 앱은 이 테이블이 없으면 `ddl-auto=validate`에 걸려 기동 자체를 거부한다. 이미 새 이미지가 crash loop 중이면 적용하는 즉시 정상 기동된다 |
| `migrate-2026-07-31-guest-converted-at.sql` | `guest.converted_at`(은퇴 시각) 컬럼 추가 — NULL 허용 | **앱 배포 전.** 컬럼 추가 자체는 구 앱과 무해하게 공존하지만, **새 앱은 이 컬럼이 없으면 `ddl-auto=validate`에 걸려 기동을 거부한다**(2026-07-31 CD 실패 원인 — `Schema-validation: missing column [converted_at] in table [guest]`). crash loop 중이면 적용하는 즉시 정상 기동된다 |
| `migrate-2026-07-31-behavior-events-not-null.sql` | `behavior_events.occurred_at`·`client_event_id`를 백필 후 `NOT NULL`로 (기존 행 삭제 없음) | **앱 배포 후.** 앱이 `occurred_at`을 채우기 시작한 뒤에야 조일 수 있다. 순서: 새 이미지 배포 → `/actuator/health` UP 확인 → 적용. 먼저 적용해도 서비스는 죽지 않지만, 그 사이 들어온 **행동 이벤트만 유실**된다(적재는 비동기) |
| `migrate-2026-08-10-review-latest-index.sql` | `review`에 `idx_review_latest(product_id, status, created_at)` 추가 — 후기 목록 정렬의 filesort 제거 (2026-08-10 부하 테스트 근거) | **순서 무관 — 언제 적용해도 된다.** 인덱스만 바뀌고 `ddl-auto=validate`는 테이블·컬럼만 검사하므로 컬럼 추가와 달리 기동을 막지 않는다. `ADD KEY`는 MariaDB에서 ONLINE이라 서비스도 멈추지 않지만 `review` 행 수만큼 시간·임시 디스크를 쓴다 — 적용 전 `SELECT COUNT(*) FROM review`로 규모를 보고, 트래픽이 한산한 때를 고르면 안전하다 |

## 5. 헬스체크

`GET /actuator/health` → `{"status":"UP"}` (db·redis 포함 — 운영자·모니터링용).

⚠️ **ALB·오케스트레이터 헬스체크 타겟은 `GET /actuator/health/liveness`** 를 쓴다 (db만 확인 — 07 §4-1).
집계 `/actuator/health`를 타겟으로 걸면 **Redis 장애가 인스턴스를 LB에서 제거해 상품·주문까지 중단**된다.
쇼핑은 Redis 없이 동작하므로 트래픽 라우팅이 Redis에 걸리면 안 된다. Redis 상태는 집계 엔드포인트와 알람으로 본다.

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
  API_PROXY_TARGET=https://<배포된 API URL>
  ```
- ⚠️ dev 서버를 공개 노출한다면 `/internal/**`는 서비스 토큰으로 보호되지만, 가능하면 인그레스에서 `/internal/**` 경로 자체를 차단 권장(운영은 nginx가 404 처리).

## 7. 배포 담당 체크리스트

- [ ] `docker build -t jarvis-backend .`
- [ ] `deploy.env` 작성 — **시크릿은 §3대로 새로 생성**, `INTERNAL_API_TOKEN`은 LLM팀과 합의, `LLM_BASE_URL`은 LLM팀에서 수령
      - `JWT_SECRET`은 **배포 서버 전용 값**으로 생성(로컬 개발값과 달라도 무방 — 각 서버가 자기 키로 서명·검증).
        기본값이 없으므로 미설정 시 기동 실패. **운영 중 교체하면 발급된 AT/RT가 전부 무효화**되어 전원 재로그인.
- [ ] **`APP_KAFKA_PREFIX=dev-` 설정 (§2-1)** — 운영 브로커를 공유하므로, 비워두면 이 서버가 운영 컨슈머 그룹에 합류해 운영 이벤트를 가져간다. 조용히 일어나므로 배포 후에는 드러나지 않는다
- [ ] 배포 DB 반영 — 신규 DB면 `docs/backend/schema.sql` + 시드 4종(§4-1), **이미 운영 중이면 `scripts/migrate-*.sql`만**(§4-2, 앱 재기동 전에)
- [ ] 컨테이너 실행 후 `/actuator/health` = UP 확인
- [ ] FE팀에 **공개 API URL** 공유 (FE는 프록시 타깃으로 사용)
- [ ] (공개 노출 시) `/internal/**` 인그레스 차단
