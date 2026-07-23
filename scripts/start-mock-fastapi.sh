#!/usr/bin/env bash
# mock-fastapi 실행 (선택 — 채팅/추천 흐름을 화면에서 확인할 때만 필요. Python 3.10+)
# 사용: bash backend/scripts/start-mock-fastapi.sh   (백엔드가 먼저 떠 있어야 JWKS 검증 가능)
# 종료: Ctrl+C
set -euo pipefail
cd "$(dirname "$0")/../../mock-fastapi"

command -v python >/dev/null 2>&1 \
  || { echo "[mock][오류] Python이 없습니다. 설치: winget install Python.Python.3.12 → 터미널 재시작" >&2; exit 1; }

if [ ! -d .venv ]; then
  echo "[mock] 가상환경 생성 중..."
  python -m venv .venv
fi
# shellcheck disable=SC1091
source .venv/Scripts/activate
pip install -q -r requirements.txt

# Spring의 app.internal.token과 동일해야 /internal/** 호출이 통과한다 (03 D4)
LOCAL_YML=../backend/src/main/resources/application-local.yml
[ -f "$LOCAL_YML" ] || { echo "[mock][오류] application-local.yml 없음 — setup-frontend-dev.sh 먼저 실행" >&2; exit 1; }
TOKEN=$(grep -A2 'internal:' "$LOCAL_YML" | grep 'token:' | head -1 | sed 's/.*token:[[:space:]]*//')
[ -n "$TOKEN" ] || { echo "[mock][오류] application-local.yml에서 internal token을 찾지 못함" >&2; exit 1; }

echo "[mock] uvicorn 기동 — http://localhost:8000"
INTERNAL_API_TOKEN="$TOKEN" SPRING_BASE_URL=http://localhost:8080 exec python -m uvicorn main:app --port 8000
