#!/usr/bin/env bash
# FE(3000) + Spring(8080) + FastAPI(8000) 라이브 계약 스모크.
set -euo pipefail

BE_URL=${BE_URL:-http://localhost:8080}
AI_URL=${AI_URL:-http://localhost:8000}
FE_URL=${FE_URL:-http://localhost:3000}
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

pass() { echo "[smoke][ok] $*"; }
fail() { echo "[smoke][fail] $*" >&2; exit 1; }

curl -fsS "$BE_URL/actuator/health" | grep -q '"UP"' || fail "Spring health"
curl -fsS "$AI_URL/health" | grep -q '"ok"' || fail "FastAPI health"
curl -fsS "$FE_URL/" | grep -q '<div id="root"></div>' || fail "Vite health"
pass "세 서버 health"

for origin in http://localhost:3000 http://localhost:5173; do
  curl -fsS -o /dev/null -D "$TMP_DIR/cors.headers" -X OPTIONS "$BE_URL/api/categories" \
    -H "Origin: $origin" -H 'Access-Control-Request-Method: GET'
  grep -qi "access-control-allow-origin: $origin" "$TMP_DIR/cors.headers" \
    || fail "Spring CORS ($origin)"
done
pass "FE → Spring CORS (3000/5173)"

curl -fsS "$BE_URL/api/categories" | grep -q '"children"' || fail "카테고리 API"
curl -fsS "$BE_URL/api/products/popular" | grep -q '"items"' || fail "인기 상품 API"
pass "홈 API 계약"

curl -fsS -c "$TMP_DIR/buyer.cookies" -H 'Content-Type: application/json' \
  -d '{"email":"buyer1@jarvis.shop","password":"seller1234"}' \
  "$BE_URL/api/auth/login" > "$TMP_DIR/buyer-login.json"
BUYER_TOKEN=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["accessToken"])' \
  "$TMP_DIR/buyer-login.json")
[ -n "$BUYER_TOKEN" ] || fail "구매자 로그인"
pass "Spring 로그인 envelope"

curl -fsS -b "$TMP_DIR/buyer.cookies" -X POST "$BE_URL/api/auth/refresh" \
  | grep -q '"accessToken"' || fail "HttpOnly cookie refresh"
pass "HttpOnly cookie refresh"

curl -fsS -H "Authorization: Bearer $BUYER_TOKEN" "$BE_URL/api/cart" \
  | grep -q '"items"' || fail "인증 장바구니"
pass "인증 API"

curl -fsS -H "Authorization: Bearer $BUYER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"channel":"SHOPPING"}' "$BE_URL/api/chat/sessions" > "$TMP_DIR/session.json"
SESSION_ID=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["sessionId"])' \
  "$TMP_DIR/session.json")
STREAM_TICKET=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["streamTicket"])' \
  "$TMP_DIR/session.json")
SSE_URL=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["llmSseUrl"])' \
  "$TMP_DIR/session.json")

curl -fsS -N --max-time 90 -H "Authorization: Bearer $STREAM_TICKET" \
  -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$SESSION_ID\",\"threadId\":\"$SESSION_ID\",\"message\":\"인기 상품 추천해줘\"}" \
  "$SSE_URL" > "$TMP_DIR/chat.sse"
grep -q '^data: {"type"' "$TMP_DIR/chat.sse" || fail "FastAPI SSE envelope"
pass "Spring 세션/RS256 티켓 → FastAPI SSE"

curl -fsS -H 'Content-Type: application/json' \
  -d '{"email":"seller@jarvis.shop","password":"seller1234"}' \
  "$BE_URL/api/auth/login" > "$TMP_DIR/seller-login.json"
SELLER_TOKEN=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["accessToken"])' \
  "$TMP_DIR/seller-login.json")
curl -fsS -H "Authorization: Bearer $SELLER_TOKEN" "$BE_URL/api/seller/summary" \
  | grep -q '"brandName"' || fail "판매자 API"
pass "판매자 API"

echo "[smoke] PASS"
