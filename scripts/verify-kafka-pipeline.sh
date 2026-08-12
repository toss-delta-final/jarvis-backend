#!/usr/bin/env bash
# 배포된 환경에서 "정말 Kafka 경로로 돌고 있는가"를 판정한다 (08 §5-6).
#
# ⚠️ 왜 이 스크립트가 필요한가 — behavior_events에 행이 쌓이는 것은 **증거가 아니다.**
#    브로커가 죽어도 프로듀서가 DB로 직접 적재하므로(08 D7 폴백) 테이블만 봐서는
#    "Kafka로 들어온 것"과 "Kafka를 못 써서 우회한 것"을 구분할 수 없다.
#    구분이 되는 신호만 골라 본다.
#
# 사용 (로컬):
#   bash scripts/verify-kafka-pipeline.sh
# 사용 (dev — Kafka EC2에서, Redis는 ElastiCache 엔드포인트):
#   KAFKA_DOCKER=jarvis-kafka BOOTSTRAP=localhost:9092 PREFIX=dev- \
#   REDIS_CLI="redis-cli -h <elasticache-endpoint>" \
#   bash scripts/verify-kafka-pipeline.sh
#   (PREFIX는 그 앱의 APP_KAFKA_PREFIX와 같은 값 — 운영을 볼 때는 생략 = 빈 값, 08 D10)
set -uo pipefail

KAFKA_DOCKER="${KAFKA_DOCKER:-jarvis-kafka}"
BOOTSTRAP="${BOOTSTRAP:-localhost:9092}"
REDIS_CLI="${REDIS_CLI:-docker exec jarvis-redis redis-cli}"
# 검사 대상 환경의 접두어 (08 D10) — 앱의 APP_KAFKA_PREFIX와 같은 값을 줘야 한다.
# 운영은 빈 값, dev 서버를 볼 때는 PREFIX=dev-. 틀리면 "그룹 조회 실패"로 나온다
PREFIX="${PREFIX:-}"
TOPIC="${PREFIX}behavior-events"

kafka() {
  MSYS_NO_PATHCONV=1 docker exec "$KAFKA_DOCKER" "/opt/kafka/bin/$1" --bootstrap-server "$BOOTSTRAP" "${@:2}"
}
redis() { $REDIS_CLI "$@"; }

fail=0
note() { printf '  %s\n' "$1"; }
ok()   { printf '✅ %s\n' "$1"; }
bad()  { printf '❌ %s\n' "$1"; fail=1; }
warn() { printf '⚠️  %s\n' "$1"; }

echo "== 1. 브로커·토픽 =="
if kafka kafka-topics.sh --describe --topic "$TOPIC" >/tmp/vk_topic 2>/dev/null; then
  ok "브로커 응답 · 토픽 존재 ($(grep -c 'Partition:' /tmp/vk_topic) 파티션)"
else
  bad "브로커에 닿지 못했다 — 이 상태면 앱은 전부 DB 폴백으로 돌고 있다"
fi

echo
echo "== 2. 컨슈머 그룹 — 위조할 수 없는 증거 =="
# 멤버가 붙어 있고 오프셋이 전진하면 앱이 실제로 브로커와 대화하고 있다는 뜻이다.
for group in "${PREFIX}persister" "${PREFIX}visitor-tracker"; do
  # ⚠️ --describe만 보면 안 된다: 앱이 죽어도 파티션 행과 커밋된 오프셋은 그대로 남아
  #    "붙어 있는 것처럼" 보인다. 활성 멤버 수는 --state의 #MEMBERS로 확인해야 한다.
  if ! kafka kafka-consumer-groups.sh --describe --group "$group" --state >/tmp/vk_s 2>/dev/null; then
    bad "$group — 그룹 조회 실패"
    continue
  fi
  state=$(awk -v g="$group" '$1 == g {print $(NF-1)}' /tmp/vk_s)
  members=$(awk -v g="$group" '$1 == g {print $NF}' /tmp/vk_s)
  if [ "${members:-0}" -eq 0 ] 2>/dev/null; then
    bad "$group — 활성 컨슈머 0 (state=${state:-?}) · 앱이 브로커에 연결돼 있지 않다"
  else
    kafka kafka-consumer-groups.sh --describe --group "$group" >/tmp/vk_g 2>/dev/null || true
    total_lag=$(awk -v t="$TOPIC" '$0 ~ t {gsub(/[^0-9-]/,"",$6); if ($6 != "" && $6 != "-") s+=$6} END{print s+0}' /tmp/vk_g)
    ok "$group — 활성 멤버 ${members} (state=${state}), 총 lag ${total_lag}"
    note "lag이 계속 커지면 컨슈머가 못 따라가는 것이고, 0 근처면 정상이다"
  fi
done

echo
echo "== 3. 컨슈머 B 산출물 — Kafka 없이는 만들어질 수 없는 것 =="
# visitors:* 는 ActiveVisitorConsumer만 쓴다(다른 기록 경로가 없다). 있으면 스트림이 실제로 돈 것이다.
keys=$(redis --scan --pattern 'visitors:*' 2>/dev/null | head -20)
if [ -n "$keys" ]; then
  ok "방문자 집합 존재 — 스트림 경로가 실제로 돌았다"
  while read -r k; do [ -n "$k" ] && note "$k → $(redis zcard "$k" 2>/dev/null) 세션"; done <<< "$keys"
else
  warn "방문자 집합이 없다 — 최근 30분간 상품 이벤트가 없었을 수도 있다(트래픽 0)."
  note "아래 4번이 정상인데 여기만 비어 있으면 '조용한 시간대'일 뿐이다"
fi

echo
echo "== 4. DLT — 적재에 끝내 실패한 레코드 =="
# 소비해도 사라지지 않으므로(보존 7일) 끝 오프셋 합 = 그 기간에 밀려난 총 건수다.
if dlt_offsets=$(kafka kafka-get-offsets.sh --topic "$TOPIC-dlt" 2>/dev/null); then
  dlt_total=$(echo "$dlt_offsets" | awk -F: '{s+=$3} END{print s+0}')
  if [ "$dlt_total" -eq 0 ]; then
    ok "DLT 비어 있음 — 최종 실패한 레코드 없음"
  else
    bad "DLT에 ${dlt_total}건 — 적재에 끝내 실패한 이벤트가 있다"
    note "원인은 앱 로그의 '적재 최종 실패' 항목. 고친 뒤 ${PREFIX}dlt-monitor 그룹 오프셋을 되감아 재처리할 수 있다"
  fi
else
  note "DLT 토픽이 아직 없다 — 실패한 적이 없다는 뜻이다(앱이 기동하면 생성된다)"
fi

echo
echo "== 5. 스트림 상태 신호 =="
connected=$(redis exists stream:behavior:connected 2>/dev/null)
degraded=$(redis exists stream:behavior:degraded 2>/dev/null)
if [ "$connected" = "1" ]; then
  since=$(redis get stream:behavior:connected 2>/dev/null)
  elapsed=$(( $(date +%s) - ${since:-0} ))
  if [ "$elapsed" -ge 1800 ]; then
    ok "브로커 연결 확인 · 연속 $((elapsed / 60))분 — 집계 창(30분)이 채워져 스트림 값을 쓴다"
  else
    warn "브로커 연결은 확인됐으나 연속 $((elapsed / 60))분뿐 — 창이 채워질 때까지 DB 폴백 (정상 동작)"
  fi
else
  bad "브로커 연결 신호 없음 — 컨슈머가 브로커와 못 통하거나 Redis에 못 쓰고 있다"
fi
if [ "$degraded" = "1" ]; then
  bad "강등 마커 있음 — 최근 30분 안에 발행 실패가 있었다 (그 구간은 DB 폴백으로 우회했다)"
  note "TTL $(redis ttl stream:behavior:degraded 2>/dev/null)초 뒤 자동 해제 · 원인은 앱 로그의 '발행 실패' 경고"
else
  ok "강등 마커 없음 — 최근 30분간 발행 실패 없음"
fi

echo
if [ "$fail" -eq 0 ]; then
  echo "판정: Kafka 경로로 동작 중."
else
  echo "판정: Kafka 경로가 성립하지 않는다 — 위 ❌ 항목 확인. 서비스는 폴백으로 계속 돈다(사용자 영향 없음)."
fi
exit "$fail"
