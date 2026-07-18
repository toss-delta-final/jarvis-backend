---
name: feature-workflow
description: Start a new feature or unit of work in jarvis-backend. Use at the START of any new feature/fix/refactor — syncs main and creates a properly-named branch BEFORE writing code, so work never lands on main directly.
---

# Feature workflow (start of a unit of work)

Run this the moment a new feature/fix begins — without being asked.

## 0. Safety check
- `git status` first. If there are uncommitted changes, stop and ask the user whether to commit, stash, or abort — don't let changes ride along across a checkout.

## 1. Sync main
```
git checkout main && git pull origin main
```

## 2. Create a branch (never commit to `main`)
One branch per unit of work. Naming: `<type>/<kebab-desc>`
- `feat/order-cancel`, `fix/cart-total`, `refactor/api-client`
- types: `feat`, `fix`, `refactor`, `docs`, `chore`, `test`, `style`

```
git checkout -b <type>/<kebab-desc>
```

## 3. Then implement, following the coding rules in CLAUDE.md
- Read surrounding code first; match its conventions.
- Layering: `Controller → Service → Repository`; DTOs for API, never expose entities.
- Never hardcode secrets; use env vars and check `.gitignore`.
- Backend spec (`docs/backend/`) is the source of truth — read before implementing.

When the work is done, use the `ship-it` skill to commit/push/PR.
