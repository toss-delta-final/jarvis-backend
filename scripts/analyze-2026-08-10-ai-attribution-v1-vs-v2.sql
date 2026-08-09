-- ============================================================
-- 진단 (2026-08-10) — AI 귀속 v1(현행) vs v2(추천 시각 기준 명단 대조) 비교
--
-- 목적: S-1 `aiAttribution`의 aiSales가 0으로만 나오는 원인이
--   ① 추천이 애초에 기록되지 않음  ② 이벤트에 listId가 안 붙음  ③ 판정 산식이 빡빡함
--   셋 중 무엇인지 가른다. 산식을 고치기 전에 그게 원인인지부터 확인하기 위한 것이다.
--
-- ⚠️ 읽기 전용 — SELECT와 사용자 변수 SET뿐이다. 운영 DB에 그대로 돌려도 안전하다.
-- ⚠️ 로컬에서는 추천 데이터가 없어 전부 0이 나온다. 의미 있는 답은 운영에서만 나온다.
--
-- 실행:
--   mariadb -h <host> -u <user> -p<pw> -t <db> < scripts/analyze-2026-08-10-ai-attribution-v1-vs-v2.sql
--
-- 읽는 법 — 섹션 B의 v1_ai / v2_ai 조합으로 원인이 갈린다:
--   v1=0, v2=0        → ①. 추천 목록 자체가 안 쌓인다. 산식 문제가 아니라 추천 파이프라인 문제다.
--   v1=0, v2>0        → ②/③. 추천은 나가는데 이벤트가 안 붙거나 판정이 빡빡하다. 산식 교체가 유효하다.
--   v1>0, v2>v1       → 둘 다 동작. 차이(onlyV2)만큼이 v2에서 새로 잡히는 분이다.
-- ============================================================

-- 대상 브랜드와 기간을 여기서 바꾼다. @to는 배타(미만)다.
SET @brandId    = 1;
SET @from       = '2026-08-01 00:00:00';
SET @to         = '2026-08-11 00:00:00';
SET @windowDays = 7;

-- ------------------------------------------------------------
-- A. 신호 점검 — 귀속 판정에 필요한 재료가 실제로 들어오고 있나
-- ------------------------------------------------------------

-- A-1. 추천 목록이 쌓이고 있나. total이 0이면 v1·v2 둘 다 0일 수밖에 없다(원인 ①).
--   GROUP BY로 쓰지 않는 이유 — 테이블이 비면 행이 아예 안 나와서 "0건"과 구별이 안 된다.
--   이 스크립트는 0을 확인하러 돌리는 것이므로 0이 눈에 보여야 한다.
SELECT 'A-1 추천 목록' AS section,
       COUNT(*)                                       AS total,
       COALESCE(SUM(source = 'AI_RECOMMENDED'), 0)    AS ai_recommended,
       COALESCE(SUM(source = 'POPULAR_FALLBACK'), 0)  AS popular_fallback
FROM recommendation_list;

-- A-2. 목록에 주인이 있나. 주인 없는 목록(세션 만료 후 콜백)은 v2가 귀속시키지 못한다.
SELECT 'A-2 목록 주인' AS section,
       COUNT(*)                                                     AS total,
       COALESCE(SUM(member_id IS NOT NULL), 0)                      AS by_member,
       COALESCE(SUM(member_id IS NULL AND guest_id IS NOT NULL), 0) AS by_guest,
       COALESCE(SUM(member_id IS NULL AND guest_id IS NULL), 0)     AS anonymous
FROM recommendation_list;

-- A-3. 이벤트에 listId가 붙고 있나. with_list가 0이면 v1은 구조적으로 0이다(원인 ②).
--      product_visible·product_click이 아예 없으면 v1의 estimated는 성립 불가다.
SELECT 'A-3 이벤트' AS section, event_type,
       COUNT(*) AS cnt, SUM(list_id IS NOT NULL) AS with_list
FROM behavior_events
GROUP BY event_type
ORDER BY cnt DESC;

-- ------------------------------------------------------------
-- B. v1 vs v2 — 같은 기간·같은 브랜드를 두 산식으로 계산해 나란히 본다
--
--   v1 = 현행. 이벤트를 따라간다. 창은 이벤트 발생 시각 기준.
--        confirmed  = AI 목록을 거쳐 담은 기록이 있다
--        estimated  = 담진 않았지만 AI 노출/클릭이 "그냥 본 것"보다 나중이다
--
--   v2 = 신안. 명단을 대조한다. 창은 추천이 나간 시각 기준(= 추천의 유효기간).
--        confirmed  = 창 안의 AI 목록을 거쳐 담은 기록이 있다
--        estimated  = 창 안의 AI 목록 명단에 그 상품이 있었다 (담기 무관)
--
--   두 버전 모두 confirmed가 우선이다 — estimated는 confirmed가 아닌 것만 센다.
-- ------------------------------------------------------------
SELECT 'B v1 vs v2' AS section,
       -- line_count가 0이면 파라미터(@brandId·기간)가 아무 주문도 못 잡은 것이다.
       -- 그 경우의 v1=v2=0은 "귀속이 안 된다"가 아니라 "볼 게 없다"는 뜻이라 구별해야 한다.
       -- (별칭을 lines로 쓰지 말 것 — MariaDB 예약어라 1064가 난다. 2026-08-08 S-1 500이 이거였다)
       COUNT(*)                                                         AS line_count,
       COALESCE(SUM(amount), 0)                                         AS totalSales,
       COALESCE(SUM(CASE WHEN v1c = 1 THEN amount END), 0)              AS v1_confirmed,
       COALESCE(SUM(CASE WHEN v1c = 0 AND v1e = 1 THEN amount END), 0)  AS v1_estimated,
       COALESCE(SUM(CASE WHEN v1c = 1 OR  v1e = 1 THEN amount END), 0)  AS v1_ai,
       COALESCE(SUM(CASE WHEN v2c = 1 THEN amount END), 0)              AS v2_confirmed,
       COALESCE(SUM(CASE WHEN v2c = 0 AND v2e = 1 THEN amount END), 0)  AS v2_estimated,
       COALESCE(SUM(CASE WHEN v2c = 1 OR  v2e = 1 THEN amount END), 0)  AS v2_ai,
       -- v1에서만 잡히던 분 — v2로 바꾸면 사라진다(주로 "오래된 추천으로 담아둔 것")
       COALESCE(SUM(CASE WHEN (v1c = 1 OR v1e = 1)
                          AND (v2c = 0 AND v2e = 0) THEN amount END), 0) AS onlyV1,
       -- v2에서만 잡히는 분 — v2로 바꾸면 새로 들어온다(이벤트 없이 명단만으로)
       COALESCE(SUM(CASE WHEN (v1c = 0 AND v1e = 0)
                          AND (v2c = 1 OR  v2e = 1) THEN amount END), 0) AS onlyV2
FROM (
  SELECT oi.price * oi.quantity AS amount,

    -- v1 confirmed — 창은 be.occurred_at 기준(목록이 언제 나갔는지는 보지 않는다)
    EXISTS (SELECT 1 FROM behavior_events be
            LEFT JOIN guest g ON g.id = be.guest_id
            JOIN recommendation_list rl ON rl.list_id = be.list_id
                                       AND rl.source = 'AI_RECOMMENDED'
            WHERE be.event_type = 'add_to_cart'
              AND be.product_id = oi.product_id
              AND COALESCE(be.member_id, g.converted_member_id) = o.member_id
              AND be.occurred_at <= o.paid_at
              AND be.occurred_at >= o.paid_at - INTERVAL @windowDays DAY) AS v1c,

    -- v1 estimated — 마지막 접점이 추천이었나(AI 노출/클릭 >= 비추천 상세조회)
    (COALESCE((SELECT MAX(be.occurred_at) FROM behavior_events be
               LEFT JOIN guest g ON g.id = be.guest_id
               JOIN recommendation_list rl ON rl.list_id = be.list_id
                                          AND rl.source = 'AI_RECOMMENDED'
               WHERE be.event_type IN ('product_visible', 'product_click')
                 AND be.product_id = oi.product_id
                 AND COALESCE(be.member_id, g.converted_member_id) = o.member_id
                 AND be.occurred_at <= o.paid_at
                 AND be.occurred_at >= o.paid_at - INTERVAL @windowDays DAY),
              '1000-01-01')
     >= COALESCE((SELECT MAX(be.occurred_at) FROM behavior_events be
                  LEFT JOIN guest g ON g.id = be.guest_id
                  WHERE be.event_type = 'product_view'
                    AND be.list_id IS NULL
                    AND be.product_id = oi.product_id
                    AND COALESCE(be.member_id, g.converted_member_id) = o.member_id
                    AND be.occurred_at <= o.paid_at
                    AND be.occurred_at >= o.paid_at - INTERVAL @windowDays DAY),
                 '1000-01-01')
     AND EXISTS (SELECT 1 FROM behavior_events be
                 LEFT JOIN guest g ON g.id = be.guest_id
                 JOIN recommendation_list rl ON rl.list_id = be.list_id
                                            AND rl.source = 'AI_RECOMMENDED'
                 WHERE be.event_type IN ('product_visible', 'product_click')
                   AND be.product_id = oi.product_id
                   AND COALESCE(be.member_id, g.converted_member_id) = o.member_id
                   AND be.occurred_at <= o.paid_at
                   AND be.occurred_at >= o.paid_at - INTERVAL @windowDays DAY)) AS v1e,

    -- v2 confirmed — 담기 기록은 같지만 창을 rl.created_at(추천이 나간 시각)으로 잰다
    EXISTS (SELECT 1 FROM behavior_events be
            LEFT JOIN guest g ON g.id = be.guest_id
            JOIN recommendation_list rl ON rl.list_id = be.list_id
                                       AND rl.source = 'AI_RECOMMENDED'
            WHERE be.event_type = 'add_to_cart'
              AND be.product_id = oi.product_id
              AND COALESCE(be.member_id, g.converted_member_id) = o.member_id
              AND be.occurred_at <= o.paid_at
              AND rl.created_at <= o.paid_at
              AND rl.created_at >= o.paid_at - INTERVAL @windowDays DAY) AS v2c,

    -- v2 estimated — 이벤트를 보지 않는다. 그 사람에게 나간 목록 명단에 있었나만 본다.
    --   목록의 주인은 rl.member_id, 비회원이면 guest 전환으로 잇는다(v1과 같은 규칙).
    EXISTS (SELECT 1 FROM recommendation_list rl
            JOIN recommendation_list_item rli ON rli.list_id = rl.list_id
            LEFT JOIN guest g ON g.id = rl.guest_id
            WHERE rl.source = 'AI_RECOMMENDED'
              AND rli.product_id = oi.product_id
              AND COALESCE(rl.member_id, g.converted_member_id) = o.member_id
              AND rl.created_at <= o.paid_at
              AND rl.created_at >= o.paid_at - INTERVAL @windowDays DAY) AS v2e

  FROM order_item oi
  JOIN orders o ON o.id = oi.order_id AND o.status = 'PAID'
  JOIN product p ON p.id = oi.product_id AND p.brand_id = @brandId
  WHERE oi.status NOT IN ('PENDING', 'CANCELLED', 'RETURNED')
    AND o.paid_at >= @from AND o.paid_at < @to
) line_flags;
