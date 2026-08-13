---
name: feature-workflow
description: Start a new feature or unit of work in jarvis-backend. Use at the START of any new feature/fix/refactor — builds a work plan with its full impact/ripple list, then syncs dev and creates a properly-named branch off dev BEFORE writing code, so work never lands on dev or main directly.
---

# Feature workflow (start of a unit of work)

Run this the moment a new feature/fix begins — without being asked.

## 0. Safety check
- `git status` first. If there are uncommitted changes, stop and ask the user whether to commit, stash, or abort — don't let changes ride along across a checkout.

## 1. Plan first — 영향 범위를 먼저 그린다

코드를 건드리기 전에 계획을 세워 **사용자에게 보여준 뒤** 시작한다. 조사(grep/Read)는 이 단계에 포함 —
"일단 고치면서 알아보기" 금지. 계획에 반드시 들어갈 세 가지:

**① 무엇을 바꾸는가** — 대상 파일·엔드포인트·DTO·설정을 구체적으로.

**② 무엇에 영향이 가는가 (연쇄 영향까지)** — 아래를 실제로 검색해 확인한 뒤 리스트업:
- **호출부 전수** — 지우거나 시그니처를 바꿀 메서드를 부르는 곳 전부(grep). 서비스 간 재사용 여부.
- **공유 자산** — 같은 DTO·상수·공용 헬퍼를 쓰는 다른 기능(건드리면 같이 깨지는 것).
- **계약 문서** — 노션 📡 API 명세서, `docs/backend/` 01–06, ERD 중 어디에 적혀 있는가.
- **다른 repo 소비자** — 형제 디렉터리 `../jarvis-frontend`, `../jarvis-ai`를 직접 grep. "안 쓸 것"으로 가정 금지.
- **인프라·주변** — `SecurityConfig` 인가 규칙, 테스트, `docs/backend/schema.sql`·시드.

**③ 계약 충돌 여부** — 계획이 **노션 API 명세나 ERD 변경을 요구하면 거기서 멈추고 보고**한다
(CLAUDE.md Contract hierarchy). 내부 문서(`docs/backend/`)는 기준에 맞춰 갱신하는 게 맞다.

## 2. Sync dev (기점은 항상 `dev` — main 아님)
```
git checkout dev && git pull origin dev
```

## 3. Create a branch (never commit to `dev` or `main`)
One branch per unit of work. Naming: `<type>/<kebab-desc>`
- `feat/order-cancel`, `fix/cart-total`, `refactor/api-client`
- types: `feat`, `fix`, `refactor`, `docs`, `chore`, `test`, `style`

```
git checkout -b <type>/<kebab-desc>
```

## 4. Then implement, following the coding rules in CLAUDE.md
- Read surrounding code first; match its conventions.
- Layering: `Controller → Service → Repository`; DTOs for API, never expose entities.
- Never hardcode secrets; use env vars and check `.gitignore`.
- 계획(1단계)에서 벗어나는 변경이 필요해지면 조용히 넓히지 말고 사용자에게 알린다.
- 계획에서 잡은 영향 대상(문서·테스트·다른 계층)은 **같은 작업 안에서 함께 갱신**한다.

When the work is done, use the `ship-it` skill to commit/push/PR.
