# backend/scripts

전부 **Git Bash**에서 실행한다 (PowerShell/cmd 불가 — openssl·리다이렉트 사용).

## 실행 스크립트

| 스크립트 | 용도 |
|---|---|
| `setup-frontend-dev.sh` | **원샷 셋업 (Docker 불필요)** — MariaDB·Redis 네이티브 확인(없으면 winget 자동 설치) → DB/계정 생성 → 스키마+증분 마이그레이션+시드 적용 → `application-local.yml`+시크릿 자동 생성. 재실행 무해. 다른 포트의 기존 DB를 쓰려면 `DB_PORT=3307 bash ...` |
| `start-backend.sh` | 백엔드 실행 — JAVA_HOME(JDK 21) 자동 탐지 후 `gradlew bootRun`. 확인: http://localhost:8080/actuator/health |
| `setup-local.sh` | (기존, 백엔드 팀용) 컨테이너 기동 + 설정 파일 복사만 — 시크릿/스키마/시드는 수동 |

## 증분 마이그레이션 (`migrate-*.sql`)

`schema.sql`은 최초 생성 전용(재실행 불가)이라, **이미 스키마가 깔린 DB**(배포 DB·기존 로컬)에
그 뒤 추가된 테이블·컬럼은 여기 마이그레이션으로 반영한다. 규칙:

- 파일명 `migrate-YYYY-MM-DD-<설명>.sql` — 날짜 접두사 오름차순 = 적용 순서
- **재실행 무해**하게 작성 — 몇 번을 돌려도 같은 상태로 수렴해야 한다. 신설은 `IF NOT EXISTS`,
  백필은 `WHERE ... IS NULL`, 제약 변경은 `MODIFY COLUMN`(이미 적용됐으면 무변경)
- `schema.sql`에도 같은 변경을 반영해 신규 DB·기존 DB의 최종 상태가 동일해야 한다
- `setup-frontend-dev.sh`가 자동 적용하므로 로컬은 셋업 재실행이면 충분. 배포 DB는 [DEPLOY.md §4-2](../DEPLOY.md)

| 파일 | 내용 | 적용 시점 |
|---|---|---|
| `migrate-2026-07-30-recommendation-list.sql` | 추천 목록 영구 사본 2개 테이블 신설 + `behavior_events` 컬럼 5개·인덱스 2개 (02 D38) | 앱 배포 **전** |
| `migrate-2026-07-31-behavior-events-not-null.sql` | `occurred_at`·`client_event_id` 백필 후 `NOT NULL` (02 D38·D40) | 앱 배포 **후** — 앱이 `occurred_at`을 채운 뒤에 조인다 |
| `migrate-2026-08-07-recommendation-attribution.sql` | `cart_item`·`order_item`에 추천 귀속 2컬럼씩 + 인덱스 (02 D43) | 앱 배포 **전** — `ddl-auto: validate`라 컬럼이 없으면 기동 자체가 실패한다 |
| `migrate-2026-08-10-review-latest-index.sql` | `review`에 `idx_review_latest(product_id, status, created_at)` 추가 — P-3 목록 정렬 filesort 제거 (02 D9 보강②) | **순서 무관.** 인덱스만 바뀌므로 `ddl-auto: validate`가 보지 않는다 — 앱 재배포 없이 DB에만 적용해도 된다 |
| `migrate-2026-08-11-erd-hardening-pre.sql` | 로그 3종 `ENUM`→`VARCHAR`, `recommendation_list_item.created_at` 신설(NULL)·백필, 인덱스 8개 제거·2개 추가 (02 D45·D46) | 앱 배포 **전** — 새 앱이 `created_at`을 찾으므로 컬럼이 없으면 기동 실패 |
| `migrate-2026-08-11-erd-hardening-post.sql` | 옵션 UNIQUE의 NULL 구멍을 가상 컬럼(`option_key`)으로 차단 + 옵션↔상품 소속 복합 FK, `created_at` NOT NULL 전환 (02 D44·D45) | 앱 배포 **후** — ① 경합이 제약 위반으로 드러나므로 이를 잡는 코드가 먼저 떠 있어야 하고 ② `created_at`은 앱이 채우기 시작한 뒤에 조인다 |

> 위 표는 **적용 시점에 주의가 필요한 것만** 싣는다. 실제 적용은 [DEPLOY.md §4-2](../DEPLOY.md)의 루프가
> `migrate-*.sql` **전부**를 날짜순으로 흘리므로, 표에 없는 파일도 빠짐없이 적용된다.

## 시드 데이터

| 파일 | 내용 | 비고 |
|---|---|---|
| `seed-accounts.sql` | 판매자(`seller@`·`seller2@`) + `buyer1~5@jarvis.shop` (전부 `seller1234`) | 최초 적용, 재실행 무해 |
| `seed-catalog.sql` | sample-100 (카테고리+브랜드+상품 100 + 옵션) | 생성기 산출물, 재실행 무해(upsert) |
| `seed-commerce-demo.sql` | seller2 브랜드 소유권 + 주문/아이템/상태로그 + 문의 데모 | accounts·catalog 선적용. 문의는 `user@jarvis.shop` 있을 때만 채움 |
| `seed-*-local.sql` | **로컬 전용**(gitignore — 커밋되지 않음). 개인 테스트 데이터. `setup-frontend-dev.sh`가 있으면 적용, 없으면 skip. **배포 DB에 적용 금지** |
| `seed-analytics-demo.sql` | behavior_events + product_change_logs + account_event_logs | commerce-demo 선적용, 재실행 무해 |

적용 순서: `seed-accounts` → `seed-catalog` → `seed-commerce-demo` → `seed-analytics-demo`

수동 적용법: `docker exec -i jarvis-mariadb mariadb -ujarvis -pjarvis-local jarvis < scripts/seed-accounts.sql` (나머지도 같은 방식)

## 벤치마크

- `bench-2026-08-10-active-visitors.sql` + `bench-active-visitors.sh` — S-1 실시간 방문자의
  **스캔(전) vs 스트림 집계(후)** 소요 비교 (08 §5-2). 격리 스키마 `jarvis_bench`에서 돌므로
  개발·운영 DB를 건드리지 않는다. 준비 절차는 `.sql` 머리말 참조.
- `verify-kafka-pipeline.sh` — 배포 환경에서 **정말 Kafka 경로로 도는지** 판정 (08 §5-5).
  폴백이 있어 DB 행·화면만으로는 구분되지 않으므로, 컨슈머 그룹 활성 멤버와 Redis 산출물만 본다.
