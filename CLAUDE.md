# CLAUDE.md — jarvis-backend

JARVIS 최종 프로젝트의 **백엔드 단독 repo** (Spring Boot). 프론트엔드는 별도 repo
(`toss-delta-final/jarvis-frontend`), LLM 팀은 FastAPI 서비스를 운영한다.

## Stack

- **Backend**: Spring Boot (Gradle), Java 21
- **DB/cache**: MariaDB 11.4 + Redis
- **Integration**: LLM 팀의 **FastAPI** 서비스와 통신할 수 있음. 백엔드↔FastAPI 인터페이스는
  구현 전에 요청/응답 스키마를 먼저 합의한다.

## Notion (team docs — search anchor)

- 노션에서 뭔가 찾을 땐 **🏁 최종 프로젝트** 페이지에서 시작: https://app.notion.com/p/be45ca79037b821aa87401726b7ac97d
- 핵심: **📡 API 명세서** (API 구현/변경 전 확인), **🧩 ADR 기록**, **🔧 트러블슈팅**, 기획서, 회의록, 일정.

## Contract hierarchy — 무엇이 기준이고 무엇이 따라가는가

1. **불변 기준 (임의 변경 금지)**: 노션 **📡 API 명세서**와 **ERD**(`docs/backend/schema.sql`). 작업 결과 이 둘을 고쳐야 한다는
   결론이 나오면 **거기서 멈추고 사용자에게 보고**해 결정을 받는다. 먼저 고치고 통보하지 않는다.
2. **정렬 대상**: repo 내부 문서(`docs/backend/` 01–06)는 위 기준에 맞춘다 — 어긋나면 **내부 문서를 고친다**.
3. **코드**: 내부 스펙을 따른다. 코드가 스펙을 벗어나야 하면 스펙을 먼저 갱신(decision-log 방식)하고 코드를 고친다.

### 반영 순서 — **노션 → 내부 문서 → 코드**. 읽을 때도 이 순서다

위 1–3은 *누가 이기는가*(권위)이고, 이건 *어느 순서로 손대는가*(작업 순서)다. **둘 다 지켜야 한다** —
"노션이 정본"이라고 말하면서 repo부터 고치는 건 규칙 위반이다.

- **읽기**: 계약 문장을 새로 쓰거나 고치기 전에 **해당 노션 페이지를 먼저 연다.** repo 내부 문서를 근거로 삼지 않는다 —
  내부 문서는 정본의 사본이라 어긋나 있을 수 있다. (실제 사고: 2026-08-06, `05-llm-contract.md`의 I-14 응답 필드명이
  `items`/`statusCounts`로 오기돼 있었고 — 정본·구현은 `rows`/`byStatus` — 그걸 사실로 믿고 새 스펙 문장을 썼다가
  뒤늦게 되돌렸다. 같은 오기를 보고 만든 LLM팀 툴은 계속 빈 결과를 읽고 있었다.)
- **쓰기**: 노션을 먼저 갱신 → 그 결과를 내부 문서에 옮김 → 마지막에 코드. 뒤집으면 **정본이 승인하지 않은 내용이
  repo에 먼저 자리잡는다.**
- 노션과 내부 문서가 어긋나면 **노션이 옳다고 보고 내부 문서를 고친다.** 노션 쪽이 틀린 것 같으면 고치지 말고 보고한다(1번 규칙).
- 예외: **ERD(`docs/backend/schema.sql`)는 repo에 있지만 불변 기준**이라 "내부 문서"가 아니다 — 변경은 사용자 결정 후.

- 백엔드 작업(구현/리뷰) 전에 `docs/backend/README.md`와 그것이 인덱싱하는 스펙 01–06을 읽는다.

## Git workflow — run automatically (details live in Skills)

### 브랜치 기점은 `dev` — main이 아니다

- **모든 작업 브랜치는 `dev`에서 딴다.** `main`은 릴리즈 라인이라 직접 기점으로 쓰지 않는다.
  PR도 `dev`를 향한다(`--base dev`). main으로의 반영은 dev→main 릴리즈 PR로만 한다.
- **`main`과 `dev`는 영구 브랜치 — 절대 삭제하지 않는다.** 로컬·원격 어느 쪽도, 어떤 정리 작업에서도 예외 없다.
  "안 쓰는 브랜치 정리" 지시를 받아도 이 둘은 대상에서 뺀다.

- **Starting** a new feature/fix → **`feature-workflow`** 스킬 실행. 코드 작성 *전에* **작업 계획 + 영향 범위(연쇄 영향 포함) 리스트업**을 먼저 내놓는다. main·dev에 직접 커밋 금지.
- **Finishing** a unit of work → **`ship-it`** 스킬 실행(pre-commit 체크 → commit → push → dev로 PR).
- **머지 완료 신호를 받으면**("머지했어" 등) → dev 동기화 + 해당 작업 브랜치 **로컬·원격 모두 삭제**. 이 repo 한정, 사전 승인 — 물어보지 말고 진행.
- Commit/push/PR도 사전 승인됨. 단 **force-push, main·dev 직접 조작, 머지-후 정리가 아닌 브랜치 삭제**는 먼저 확인.

## Coding rules

**General**
- 주변 코드를 먼저 읽고 컨벤션·네이밍·구조를 맞춘다. 요청 없이 새 패턴 도입 금지.
- 주석은 *why*만. 자명한 코드엔 주석 생략.
- 시크릿/토큰/DB 자격증명 하드코딩 금지. env(`.env`, `application.yml`) 사용하고 `.gitignore` 확인.

**Backend (Spring Boot)**
- 레이어링 준수: `Controller → Service → Repository`. 컨트롤러에 비즈니스 로직 금지.
- 요청/응답은 DTO 사용. 엔티티를 API 응답에 직접 노출 금지.
- 빌드는 `./gradlew`. JDK/Gradle이 PATH에 없으므로 `JAVA_HOME`을 명시(OpenJDK 21)해 실행.

**FastAPI integration (LLM team)**
- 통신 스키마를 먼저 합의·문서화.
- 모든 아웃바운드 호출에 timeout, 에러 처리, 재시도 정책 포함.

## Token economy (how Claude should work here)

- 파일 전체를 무작정 읽지 말 것 — Read는 offset/limit, 또는 Grep/Glob으로 먼저 좁힌다.
- 간결하게: 여러 옵션 나열 대신 하나 추천, 명령 출력 그대로 반복 금지.

## Run / build

- **Backend**: `./gradlew bootRun` — `JAVA_HOME` 명시(Microsoft OpenJDK 21, 이 머신 예:
  `C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot`).
- **로컬 원샷 셋업**(Git Bash): `bash scripts/setup-frontend-dev.sh` → `bash scripts/start-backend.sh`
  (MariaDB·Redis 확인/설치 → DB·스키마·시드 → 설정·시크릿 생성 → 기동). 확인: http://localhost:8080/actuator/health
- **배포**: dev 서버 산출물은 `Dockerfile` + `DEPLOY.md` 참조. 인프라는 배포 담당 소관.
- **Frontend**: 별도 repo `toss-delta-final/jarvis-frontend`.
