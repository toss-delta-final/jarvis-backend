---
name: ship-it
description: Finish a unit of work in jarvis-backend — run pre-commit checks, then commit, push, and open a PR to dev; and clean up the branch once the user says it's merged. Use when a feature/fix is ready to be committed/pushed/PR'd, or when the user signals a merge landed ("머지했어"/"merged"). Commit/push/PR and post-merge branch deletion (local + remote) are pre-authorized; force-push, main/dev changes, deleting main or dev, and branch deletion outside the post-merge flow need explicit confirmation first.
---

# Ship it (commit → push → PR)

## 1. Pre-commit checklist (run what applies)
- **Build** with explicit `JAVA_HOME` (OpenJDK 21): `./gradlew build`.
- **Verify it works** — drive the affected flow or run the relevant tests, don't assume.
- **No secrets** committed (tokens/DB creds); confirm `.gitignore` covers `.env` etc.
- On a feature branch, **not** `main`/`dev` (if on either, stop and run `feature-workflow` first).

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
gh pr create --base dev --fill
```
`--base dev`가 기본이다 — `main`은 릴리즈 라인이라 dev→main 릴리즈 PR에서만 대상이 된다.
The user decides whether to merge — do not merge automatically.

## 5. 머지 완료 신호를 받으면 — 브랜치 정리 (묻지 말고 실행)

사용자가 머지를 알리면("머지했어", "머지 완료", "merged" 등) 확인 절차 없이 바로 정리한다.
**jarvis-backend repo 한정** 규칙 — 다른 repo에는 적용하지 않는다.

```
git checkout dev
git pull origin dev
git branch -d <branch>              # -D 아님: 머지 안 됐으면 실패하게 두고 사용자에게 보고
git push origin --delete <branch>   # GitHub 자동 삭제로 이미 없으면 그냥 넘어감
```

- 삭제 대상이 `main`이나 `dev`면 **실행하지 않는다** — 영구 브랜치라 정리 대상이 아니다.
- `-d`가 "not fully merged"로 거절하면 **강제 삭제하지 말고** 상황을 보고한다(머지 신호가 틀렸을 수 있음).
- 원격 삭제가 "remote ref does not exist"면 이미 정리된 것 — 무해하니 그대로 진행.
- 정리 후 `git branch` 결과로 삭제를 확인하고 한 줄로 보고한다.

## Guardrail
Steps 1–5 are pre-authorized — 머지 후 브랜치 정리(5단계)도 포함. **Confirm first** before
force-push, any direct manipulation of `main`/`dev`, or deleting a branch **outside** the step-5 flow
(머지 신호 없이, 또는 `-D` 강제 삭제). **`main`·`dev` 삭제는 확인을 받아도 하지 않는다.**
