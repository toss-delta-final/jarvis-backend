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

## Backend spec — read before implementing

- 백엔드 작업(구현/리뷰) 전에 `docs/backend/README.md`와 그것이 인덱싱하는 스펙 01–06을 읽는다.
  스펙이 소스 오브 트루스: 코드가 벗어나야 하면 스펙을 먼저 갱신(decision-log 방식)하고 코드를 고친다.

## Git workflow — run automatically (details live in Skills)

- **Starting** a new feature/fix → **`feature-workflow`** 스킬 실행(main 동기화 + 브랜치 생성)을 코드 작성 *전에*. main에 직접 커밋 금지.
- **Finishing** a unit of work → **`ship-it`** 스킬 실행(pre-commit 체크 → commit → push → main으로 PR).
- Commit/push/PR은 사전 승인됨 — 물어보지 말고 진행. 단 **force-push, 브랜치 삭제, main 직접 조작**은 먼저 확인.

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
- 방금 편집한 파일을 확인용으로 다시 읽지 말 것(Edit은 실패 시 에러남).
- 넓은 탐색은 Explore/Agent에 위임하고 결론만 취한다.
- 확정된 결정을 재설명/재論의하지 말 것. 정보가 충분하면 실행.
- 간결하게: 여러 옵션 나열 대신 하나 추천, 명령 출력 그대로 반복 금지.

## Run / build

- **Backend**: `./gradlew bootRun` — `JAVA_HOME` 명시(Microsoft OpenJDK 21, 이 머신 예:
  `C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot`).
- **로컬 원샷 셋업**(Git Bash): `bash scripts/setup-frontend-dev.sh` → `bash scripts/start-backend.sh`
  (MariaDB·Redis 확인/설치 → DB·스키마·시드 → 설정·시크릿 생성 → 기동). 확인: http://localhost:8080/actuator/health
- **배포**: dev 서버 산출물은 `Dockerfile` + `DEPLOY.md` 참조. 인프라는 배포 담당 소관.
- **Frontend**: 별도 repo `toss-delta-final/jarvis-frontend`.
