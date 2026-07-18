#!/usr/bin/env bash
# 로컬 개발 환경 셋업: DB 컨테이너 기동 + 설정 파일 준비.
# 사용: bash scripts/setup-local.sh  (Windows는 Git Bash / WSL)
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  cp .env.example .env
  echo "[setup] .env 생성 (.env.example 복사) — 필요 시 값 수정"
fi

if [ ! -f src/main/resources/application-local.yml ]; then
  cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
  echo "[setup] application-local.yml 생성"
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "[setup] docker를 찾을 수 없습니다 — MariaDB(3306)·Redis(6379)를 다른 방법으로 띄웠다면 무시해도 됩니다." >&2
  exit 1
fi

docker compose up -d --wait
echo "[setup] MariaDB(3306)·Redis(6379) healthy — 이제 ./gradlew bootRun (JAVA_HOME 필요, README 참조)"
