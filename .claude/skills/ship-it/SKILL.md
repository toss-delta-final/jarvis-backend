---
name: ship-it
description: Finish a unit of work in jarvis-backend — run pre-commit checks, then commit, push, and open a PR to main. Use when a feature/fix is done and ready to be committed/pushed/PR'd. Commit/push/PR are pre-authorized; force-push/branch-deletion/main changes need explicit confirmation first.
---

# Ship it (commit → push → PR)

## 1. Pre-commit checklist (run what applies)
- **Build** with explicit `JAVA_HOME` (OpenJDK 21): `./gradlew build`.
- **Verify it works** — drive the affected flow or run the relevant tests, don't assume.
- **No secrets** committed (tokens/DB creds); confirm `.gitignore` covers `.env` etc.
- On a feature branch, **not** `main` (if on main, stop and run `feature-workflow` first).

## 2. Commit (Conventional Commits, logical units)
- Prefixes: `feat:`, `fix:`, `refactor:`, `docs:`, `chore:`, `test:`, `style:`.
- Split unrelated changes into separate commits.
- **Do NOT add a `Co-Authored-By: Claude` trailer.**

## 3. Push
```
git push -u origin <branch>
```

## 4. PR
```
gh pr create --base main --fill
```
The user decides whether to merge — do not merge automatically.

## Guardrail
Steps 1–4 are pre-authorized. **Confirm first** before force-push, branch deletion,
or any direct manipulation of `main`.
