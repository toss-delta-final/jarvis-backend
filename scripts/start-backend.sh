#!/usr/bin/env bash
# 백엔드 실행 (Git Bash): JAVA_HOME 자동 탐지 → gradlew bootRun
# 사용: bash backend/scripts/start-backend.sh   (리포 어디서 실행해도 됨)
# 종료: Ctrl+C
set -euo pipefail
cd "$(dirname "$0")/.."   # → backend/

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  for d in "/c/Program Files/Microsoft/jdk-21"* \
           "/c/Program Files/Eclipse Adoptium/jdk-21"* \
           "/c/Program Files/Java/jdk-21"*; do
    if [ -d "$d" ]; then JAVA_HOME=$d; break; fi
  done
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "[run][오류] JDK 21을 찾지 못했습니다." >&2
  echo "  설치: winget install Microsoft.OpenJDK.21  → 설치 후 터미널을 새로 열고 재실행" >&2
  exit 1
fi
if (exec 3<>/dev/tcp/127.0.0.1/8080) 2>/dev/null; then
  exec 3>&- 3<&- || true
  echo "[run][오류] 8080 포트가 이미 사용 중입니다 — 백엔드가 이미 떠 있는지 확인하세요." >&2
  echo "  확인: http://localhost:8080/actuator/health 가 응답하면 그대로 쓰면 됩니다." >&2
  exit 1
fi

export JAVA_HOME
echo "[run] JAVA_HOME=$JAVA_HOME"
echo "[run] 백엔드 기동 중... 'Started BackendApplication'이 보이면 성공"
echo "[run] 확인: http://localhost:8080/actuator/health"
exec ./gradlew bootRun
