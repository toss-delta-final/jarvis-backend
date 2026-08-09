#!/usr/bin/env bash
# S-1 실시간 방문자 — 스캔(전) vs 스트림 집계(후) 소요 비교 (08 §5-2)
#
# 전제: docker compose 기동 + jarvis_bench 스키마 준비 (bench-2026-08-10-active-visitors.sql 머리말)
# 사용: bash scripts/bench-active-visitors.sh
#
# 왜 여러 수준을 재나 — 스캔 비용은 테이블 크기가 아니라 **최근 30분 이벤트 수**에 비례한다.
# 한 지점만 재면 "우리 규모에선 안 느리다"에도, "그러니 필요하다"에도 답할 수 없다.
set -euo pipefail
cd "$(dirname "$0")/.."

DB=(docker exec jarvis-mariadb mariadb -ujarvis -pjarvis-local jarvis_bench -N -B)
REDIS=(docker exec jarvis-redis redis-cli)
RUNS=5
REPEAT=10000

# 커맨드 1회당 소요(ms) — 프로세스 기동을 REPEAT회로 상각한다
per_op() {
  local start end
  start=$(date +%s%N)
  "${REDIS[@]}" -r $REPEAT -i 0 "$@" >/dev/null
  end=$(date +%s%N)
  awk "BEGIN{printf \"%.4f\", ($end - $start)/1000000/$REPEAT}"
}

printf '%-12s %-10s %-12s %-12s\n' "최근30분" "방문자" "스캔(전)ms" "ZCARD(후)ms"

for n in 1000 10000 50000 200000; do
  "${DB[@]}" -e "CALL bench_fill_recent($n);" >/dev/null

  # 전 — S-1 폴백 쿼리 그대로. 첫 회는 버퍼풀 워밍업이라 버리고 최소값을 쓴다
  "${DB[@]}" -e "CALL bench_measure(1);" >/dev/null
  scan_best=""
  visitors=""
  for _ in $(seq $RUNS); do
    row=$("${DB[@]}" -e "CALL bench_measure(1);")
    visitors=$(echo "$row" | awk '{print $1}')
    ms=$(echo "$row" | awk '{print $2}')
    if [ -z "$scan_best" ] || awk "BEGIN{exit !($ms < $scan_best)}"; then scan_best=$ms; fi
  done

  # 후 — 같은 카디널리티의 ZSET에 대해 ActiveVisitorStore.count와 같은 연산(정리 + ZCARD).
  # docker exec + redis-cli 기동이 ~400ms라 1회 측정은 그 비용에 묻힌다 → 1만 회로 상각한다.
  # 네트워크 왕복은 앱도 치르는 비용이라 빼지 않고, PING 기준선으로 얼마가 왕복인지만 보여준다.
  "${REDIS[@]}" del bench:visitors >/dev/null
  now=$(date +%s)
  # 파이프 대신 임시 파일 — 대량 입력에서 writer가 SIGPIPE로 죽으면 pipefail이 스크립트를 끊는다
  seed_file=$(mktemp)
  for i in $(seq "$visitors"); do
    echo "zadd bench:visitors $((now - i % 1800)) sess-$i"
  done > "$seed_file"
  docker exec -i jarvis-redis redis-cli --pipe < "$seed_file" >/dev/null 2>&1 || true
  rm -f "$seed_file"
  zcard_ms=$(per_op zcard bench:visitors)

  printf '%-12s %-10s %-12s %-12s\n' "$n" "$visitors" "$scan_best" "$zcard_ms"
done

ping_ms=$(per_op ping)
"${REDIS[@]}" del bench:visitors >/dev/null
echo
echo "기준선: PING 1회 ${ping_ms}ms — ZCARD 값에서 이만큼은 왕복(앱도 치르는 비용)이다."
echo "스캔은 MariaDB 안에서 NOW(6)로 잰 서버 시간이라 클라이언트 비용이 없다."
