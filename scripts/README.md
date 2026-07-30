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
- **재실행 무해**(`IF NOT EXISTS`)로 작성 — 몇 번을 돌려도 같은 상태로 수렴해야 한다
- `schema.sql`에도 같은 변경을 반영해 신규 DB·기존 DB의 최종 상태가 동일해야 한다
- `setup-frontend-dev.sh`가 자동 적용하므로 로컬은 셋업 재실행이면 충분. 배포 DB는 [DEPLOY.md §4-2](../DEPLOY.md)

| 파일 | 내용 |
|---|---|
| `migrate-2026-07-30-recommendation-list.sql` | 추천 목록 영구 사본 2개 테이블 신설 + `behavior_events` 컬럼 5개·인덱스 2개 (02 D38) |

## 시드 데이터

| 파일 | 내용 | 비고 |
|---|---|---|
| `seed-accounts.sql` | 판매자(`seller@`·`seller2@`) + `buyer1~5@jarvis.shop` (전부 `seller1234`) | 최초 적용, 재실행 무해 |
| `seed-catalog.sql` | sample-100 (카테고리+브랜드+상품 100 + 옵션) | 생성기 산출물, 재실행 무해(upsert) |
| `seed-commerce-demo.sql` | seller2 브랜드 소유권 + 주문/아이템/상태로그 + 문의 데모 | accounts·catalog 선적용. 문의는 `user@jarvis.shop` 있을 때만 채움 |
| `seed-analytics-demo.sql` | behavior_events + product_change_logs + account_event_logs | commerce-demo 선적용, 재실행 무해 |

적용 순서: `seed-accounts` → `seed-catalog` → `seed-commerce-demo` → `seed-analytics-demo`

수동 적용법: `docker exec -i jarvis-mariadb mariadb -ujarvis -pjarvis-local jarvis < scripts/seed-accounts.sql` (나머지도 같은 방식)
