#!/usr/bin/env bash
# 프론트엔드 개발자용 원샷 셋업 (Git Bash 전용, Docker 불필요 — 전부 네이티브 설치):
#   MariaDB·Redis 확인(없으면 winget 자동 설치) → DB/계정 생성 → 스키마+시드 적용 → 설정 파일+시크릿 생성
# 사용: bash backend/scripts/setup-frontend-dev.sh   (리포 어디서 실행해도 됨)
# 다시 실행해도 안전 — 이미 만든 DB/설정/데이터는 건드리지 않는다.
set -euo pipefail
cd "$(dirname "$0")/.."   # → backend/

say()  { echo "[setup] $*"; }
fail() { echo "[setup][오류] $*" >&2; exit 1; }

DB_NAME=jarvis; DB_USER=jarvis; DB_PASS=jarvis-local
DB_PORT=${DB_PORT:-3306}   # 기본 3306 (다른 포트의 기존 서버를 쓰려면 DB_PORT=xxxx 로 실행)

port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && { exec 3>&- 3<&- || true; return 0; } || return 1; }

# ── 0) 기본 도구 확인 ──────────────────────────────────────────
command -v openssl >/dev/null 2>&1 \
  || fail "openssl이 없습니다. Git Bash에서 실행했는지 확인하세요 (PowerShell/cmd 불가)."
command -v winget.exe >/dev/null 2>&1 || say "winget 없음 — 자동 설치는 불가, 설치돼 있는 것만 사용"

# ── 1) MariaDB: 클라이언트 탐지 → 없으면 winget 설치 ─────────────
find_mariadb_client() {
  local d
  for d in "/c/Program Files/MariaDB"*/bin; do
    if [ -x "$d/mariadb.exe" ]; then echo "$d/mariadb.exe"; return; fi
    if [ -x "$d/mysql.exe" ];   then echo "$d/mysql.exe";   return; fi
  done
  command -v mariadb 2>/dev/null || command -v mysql 2>/dev/null || true
}

CLIENT=$(find_mariadb_client)
if [ -z "$CLIENT" ]; then
  say "MariaDB가 없어 설치합니다 (관리자 승인 창이 뜨면 '예' — 1~2분 소요)"
  winget.exe install MariaDB.Server --version 11.4.3.0 --accept-package-agreements --accept-source-agreements \
    || fail "MariaDB 자동 설치 실패 — PowerShell(관리자)에서 직접 실행: winget install MariaDB.Server --version 11.4.3.0"
  CLIENT=$(find_mariadb_client)
  [ -n "$CLIENT" ] || fail "설치 직후 클라이언트를 못 찾았습니다 — 터미널을 새로 열고 다시 실행하세요."
fi
say "MariaDB 클라이언트: $CLIENT"

port_in_use "$DB_PORT" \
  || fail "MariaDB 서버가 실행 중이 아닙니다(포트 $DB_PORT). Windows 검색 → '서비스' → 'MariaDB' 시작 후 재실행하세요."

MDB() { "$CLIENT" --default-character-set=utf8mb4 -h127.0.0.1 -P"$DB_PORT" "$@"; }

# ── 2) DB·계정 생성 (이미 있으면 그대로 통과) ──────────────────────
if MDB -u"$DB_USER" -p"$DB_PASS" -e "SELECT 1" "$DB_NAME" >/dev/null 2>&1; then
  say "DB($DB_NAME)·계정($DB_USER) 이미 준비됨"
else
  say "DB·계정 생성 중 (root로 접속 시도)"
  BOOTSTRAP_SQL="CREATE DATABASE IF NOT EXISTS $DB_NAME CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '$DB_USER'@'localhost' IDENTIFIED BY '$DB_PASS';
CREATE USER IF NOT EXISTS '$DB_USER'@'127.0.0.1' IDENTIFIED BY '$DB_PASS';
GRANT ALL PRIVILEGES ON $DB_NAME.* TO '$DB_USER'@'localhost';
GRANT ALL PRIVILEGES ON $DB_NAME.* TO '$DB_USER'@'127.0.0.1';
FLUSH PRIVILEGES;"
  # 신규 설치 root는 비밀번호 없음. 기존 서버라면 root 비밀번호를 물어본다.
  if MDB -uroot -e "$BOOTSTRAP_SQL" 2>/dev/null; then
    say "DB·계정 생성 완료 (root 무비밀번호)"
  else
    echo "[setup] root 비밀번호가 설정된 서버입니다 — root 비밀번호를 입력하세요:"
    MDB -uroot -p -e "$BOOTSTRAP_SQL" \
      || fail "root 접속 실패 — DB 관리자에게 위 CREATE/GRANT 문 실행을 요청하세요."
    say "DB·계정 생성 완료"
  fi
  MDB -u"$DB_USER" -p"$DB_PASS" -e "SELECT 1" "$DB_NAME" >/dev/null 2>&1 \
    || fail "생성한 계정으로 접속이 안 됩니다 — 다시 실행해보고, 반복되면 문의 주세요."
fi

# ── 3) 스키마 적용 (이미 적용됐으면 건너뜀 — schema.sql은 재실행 불가 DDL) ──
HAS_MEMBER=$(MDB -u"$DB_USER" -p"$DB_PASS" -N -e "SHOW TABLES LIKE 'member'" "$DB_NAME")
if [ -z "$HAS_MEMBER" ]; then
  say "스키마 적용 중 (docs/backend/schema.sql)"
  MDB -u"$DB_USER" -p"$DB_PASS" "$DB_NAME" < ../docs/backend/schema.sql
else
  say "스키마 이미 적용됨 — 건너뜀"
fi

# ── 4) 시드 적용 (전부 재실행 무해: INSERT IGNORE / NOT EXISTS) ──
for f in scripts/seed-phase1.sql scripts/seed-phase2.sql scripts/seed-phase6.sql; do
  say "시드 적용: $f"
  MDB -u"$DB_USER" -p"$DB_PASS" "$DB_NAME" < "$f"
done
say "(선택) seed-phase4.sql은 user@jarvis.shop 가입 후 수동 적용 — 파일 헤더 참조"

# ── 5) Redis: 실행 확인 → 없으면 winget 설치 (Windows 서비스로 등록됨) ──
if port_in_use 6379; then
  say "Redis 이미 실행 중 (6379)"
else
  if [ ! -x "/c/Program Files/Redis/redis-server.exe" ]; then
    say "Redis가 없어 설치합니다 (관리자 승인 창이 뜨면 '예')"
    winget.exe install Redis.Redis --accept-package-agreements --accept-source-agreements \
      || fail "Redis 자동 설치 실패 — PowerShell(관리자)에서 직접 실행: winget install Redis.Redis"
  fi
  if ! port_in_use 6379; then
    say "Redis 서비스 시작 대기 중..."
    sleep 3
    port_in_use 6379 \
      || fail "Redis가 실행되지 않았습니다. Windows 검색 → '서비스' → 'Redis' 시작 후 재실행하세요."
  fi
  say "Redis 실행 확인 (6379)"
fi

# ── 6) application-local.yml 생성 + 시크릿 4종 자동 생성 ──────────
#     항목 구성은 application-local.yml.example과 동일하게 유지할 것 (03 §5)
LOCAL_YML=src/main/resources/application-local.yml
if [ -f "$LOCAL_YML" ]; then
  say "application-local.yml 이미 존재 — 유지"
else
  JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')
  STREAM_KEY=$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null \
               | openssl pkcs8 -topk8 -nocrypt -outform DER | base64 | tr -d '\n')
  INTERNAL_TOKEN=$(openssl rand -hex 32)
  KID="jarvis-local-$(date +%Y-%m)"
  cat > "$LOCAL_YML" <<EOF
# setup-frontend-dev.sh가 생성한 로컬 설정 (gitignore — 03 §5). 시크릿은 이 머신 전용 랜덤 값.
spring:
  datasource:
    url: jdbc:mariadb://localhost:${DB_PORT}/${DB_NAME}
    username: ${DB_USER}
    password: ${DB_PASS}
  data:
    redis:
      host: localhost
      port: 6379

jwt:
  secret: ${JWT_SECRET}

app:
  stream-ticket:
    private-key: ${STREAM_KEY}
    kid: ${KID}
  internal:
    token: ${INTERNAL_TOKEN}
  llm:
    base-url: http://localhost:8000 # mock-fastapi 미기동이어도 무해 (통지 실패는 warn 로그만)
    sse-url: http://localhost:8000  # CH-1 응답 llmSseUrl — FE가 직결할 SSE URL
EOF
  say "application-local.yml 생성 (JWT/스트림키/내부토큰 자동 생성)"
fi

echo ""
say "✅ 셋업 완료! 다음 단계:"
say "  1. 백엔드 실행:   bash backend/scripts/start-backend.sh"
say "  2. 동작 확인:     http://localhost:8080/actuator/health → {\"status\":\"UP\"}"
say "  3. API 문서:      http://localhost:8080/swagger-ui/index.html"
say "  테스트 계정: seller@jarvis.shop / buyer1~5@jarvis.shop (비밀번호 모두 seller1234)"
