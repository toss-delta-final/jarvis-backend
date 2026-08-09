-- S-1 실시간 방문자 — 스트림 집계 전/후 비교 벤치 (08 §5-2)
--
-- 측정 대상: 대시보드 진입마다 돌던 "최근 30분 스캔"의 비용.
-- 이 비용은 behavior_events 전체 크기가 아니라 **최근 30분에 들어온 이벤트 수**에 비례한다
-- (`created_at >= now()-30m`이 인덱스 범위로 좁혀지므로). 그래서 트래픽 수준을 여러 단계로
-- 만들어 비교한다 — 한 지점만 재면 "우리 규모에선 안 느리다"는 반론에 답할 수 없다.
--
-- 격리: 운영/개발 DB를 건드리지 않도록 **별도 스키마**(jarvis_bench)에서 돌린다.
--   docker exec jarvis-mariadb mariadb -uroot -proot-local -e "CREATE DATABASE jarvis_bench CHARACTER SET utf8mb4"
--   docker exec -i jarvis-mariadb mariadb -uroot -proot-local jarvis_bench < docs/backend/schema.sql
--   docker exec -i jarvis-mariadb mariadb -ujarvis -pjarvis-local jarvis_bench < scripts/bench-2026-08-10-active-visitors.sql
--
-- 실행은 scripts/bench-active-visitors.sh 참조.

DROP PROCEDURE IF EXISTS bench_fill_recent;
DELIMITER //
-- 최근 30분에 n건을 흩뿌린다. 세션당 8건(실사용 근사), 상품 id 짝수 = 브랜드 1.
-- 10%는 챗봇 sentinel 세션 — 제외 규칙(2026-08-10 정정)이 실제로 걸러내는지 같이 본다.
CREATE PROCEDURE bench_fill_recent(IN n INT)
BEGIN
    -- 격리된 벤치 스키마라 전량 삭제해도 안전하다
    DELETE FROM behavior_events;
    INSERT INTO behavior_events
        (member_id, guest_id, session_key, client_event_id, event_type, product_id,
         properties, occurred_at, created_at)
    SELECT NULL, NULL,
           CASE WHEN seq % 10 = 0
                THEN CONCAT('chat:', FLOOR(seq / 8))
                ELSE CONCAT('bench-sess-', FLOOR(seq / 8)) END,
           UUID(),   -- client_event_id는 CHAR(36) — 접두사를 붙이면 넘친다
           'product_view',
           (seq % 200) + 1,
           NULL,
           NOW(6) - INTERVAL (seq % 1800) SECOND,
           NOW(6) - INTERVAL (seq % 1800) SECOND
    FROM seq_1_to_1000000
    WHERE seq <= n;
END //

DROP PROCEDURE IF EXISTS bench_measure//
-- S-1 폴백 쿼리(BehaviorEventRepository.countActiveVisitors)를 그대로 재현해 소요를 잰다.
CREATE PROCEDURE bench_measure(IN brand BIGINT)
BEGIN
    DECLARE t0 DATETIME(6);
    DECLARE cnt BIGINT;
    SET t0 = NOW(6);
    SELECT COUNT(DISTINCT be.session_key) INTO cnt
      FROM behavior_events be
      JOIN product p ON p.id = be.product_id AND p.brand_id = brand
     WHERE be.created_at >= NOW() - INTERVAL 30 MINUTE
       AND be.session_key NOT LIKE 'chat:%';
    SELECT cnt AS visitors, TIMESTAMPDIFF(MICROSECOND, t0, NOW(6)) / 1000 AS scan_ms;
END //
DELIMITER ;
