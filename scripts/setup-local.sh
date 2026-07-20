#!/usr/bin/env bash
# Docker 기반 로컬 통합 셋업: 인프라 → 스키마/시드 → 로컬 시크릿 설정.
set -euo pipefail
cd "$(dirname "$0")/.."

say() { echo "[setup] $*"; }
fail() { echo "[setup][오류] $*" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || fail "docker를 찾을 수 없습니다."
command -v openssl >/dev/null 2>&1 || fail "openssl을 찾을 수 없습니다."

if [ ! -f .env ]; then
  cp .env.example .env
  say ".env 생성 (.env.example 복사)"
fi

docker compose up -d --wait
say "MariaDB·Redis healthy"

mdb() {
  docker compose exec -T mariadb mariadb \
    --default-character-set=utf8mb4 -ujarvis -pjarvis-local jarvis "$@"
}

if ! mdb -N -e "SHOW TABLES LIKE 'member'" | grep -q '^member$'; then
  say "스키마 적용: docs/backend/schema.sql"
  mdb < docs/backend/schema.sql
fi

for seed in scripts/seed-phase1.sql scripts/seed-phase2.sql scripts/seed-phase6.sql \
            scripts/seed-sample-100.sql; do
  say "시드 적용: $seed"
  mdb < "$seed"
done

LOCAL_YML=src/main/resources/application-local.yml
if [ ! -f "$LOCAL_YML" ]; then
  JWT_SECRET=$(openssl rand -base64 48 | tr -d '\r\n')
  STREAM_KEY=$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null \
    | openssl pkcs8 -topk8 -nocrypt -outform DER | base64 | tr -d '\r\n')
  INTERNAL_TOKEN=$(openssl rand -hex 32)
  KID="jarvis-local-$(date +%Y-%m)"
  cat > "$LOCAL_YML" <<EOF
# setup-local.sh가 생성한 머신 전용 설정 (gitignore).
spring:
  datasource:
    url: jdbc:mariadb://localhost:3306/jarvis
    username: jarvis
    password: jarvis-local
  data:
    redis:
      host: localhost
      port: 6379
  cloud:
    aws:
      credentials:
        access-key: local
        secret-key: local
      region:
        static: ap-northeast-2
      s3:
        bucket: jarvis-local

jwt:
  secret: ${JWT_SECRET}

app:
  cookie:
    secure: false
  stream-ticket:
    private-key: ${STREAM_KEY}
    kid: ${KID}
  internal:
    token: ${INTERNAL_TOKEN}
  llm:
    base-url: http://localhost:8000
    sse-url: http://localhost:8000
EOF
  say "application-local.yml 생성 (JWT·RS256·internal token 자동 생성)"
else
  say "application-local.yml 기존 파일 유지"
fi

say "셋업 완료 — bash scripts/start-backend.sh"
