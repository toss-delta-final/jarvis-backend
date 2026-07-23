# backend/scripts

전부 **Git Bash**에서 실행한다 (PowerShell/cmd 불가 — openssl·리다이렉트 사용).

## 실행 스크립트

| 스크립트 | 용도 |
|---|---|
| `setup-frontend-dev.sh` | **원샷 셋업 (Docker 불필요)** — MariaDB·Redis 네이티브 확인(없으면 winget 자동 설치) → DB/계정 생성 → 스키마+시드(phase1·2·6) 적용 → `application-local.yml`+시크릿 자동 생성. 재실행 무해. 다른 포트의 기존 DB를 쓰려면 `DB_PORT=3307 bash ...` |
| `start-backend.sh` | 백엔드 실행 — JAVA_HOME(JDK 21) 자동 탐지 후 `gradlew bootRun`. 확인: http://localhost:8080/actuator/health |
| `setup-local.sh` | (기존, 백엔드 팀용) 컨테이너 기동 + 설정 파일 복사만 — 시크릿/스키마/시드는 수동 |

## 시드 데이터

| 파일 | 내용 | 비고 |
|---|---|---|
| `seed-phase1.sql` | 판매자 1호 (`seller@jarvis.shop` / `seller1234`) | 재실행 무해 |
| `seed-phase2.sql` | 카테고리 + 상품 50개 | 재실행 무해 |
| `seed-phase4.sql` | 문의 데모 | **선행: `user@jarvis.shop` 가입 API 호출** — 수동 적용 |
| `seed-phase6.sql` | 판매자 2호 + `buyer1~5@jarvis.shop`(전부 `seller1234`) + 주문/로그 더미 | phase1·2 선적용 전제, 재실행 무해 |

수동 적용법: `docker exec -i jarvis-mariadb mariadb -ujarvis -pjarvis-local jarvis < scripts/seed-phaseN.sql`
