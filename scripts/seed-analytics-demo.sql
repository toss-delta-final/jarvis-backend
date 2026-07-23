-- 시드 — 분석/로그 데모 (behavior_events + product_change_logs + account_event_logs)
-- 기준: seller2 소유 가전/디지털 7종(seed-commerce-demo.sql 참조). 상품 id는 ELT로 7종에서 순환 선택.
--   7종: 2376177306 9233450819 9236338700 9349985278 9381716646 9434825492 9470646527
-- 날짜는 NOW() 기준 상대값. 재실행 무해(INSERT IGNORE + client_event_id UNIQUE / NOT EXISTS).
-- 사전: seed-accounts.sql + seed-catalog.sql + seed-commerce-demo.sql 선적용.
-- MariaDB SEQUENCE 엔진(seq_1_to_N) 사용.
-- 적용: docker exec -i jarvis-mariadb mariadb -ujarvis -pjarvis-local jarvis < scripts/seed-analytics-demo.sql

SET @buyer1 = (SELECT id FROM member WHERE email = 'buyer1@jarvis.shop');
SET @buyer2 = (SELECT id FROM member WHERE email = 'buyer2@jarvis.shop');
SET @buyer3 = (SELECT id FROM member WHERE email = 'buyer3@jarvis.shop');
SET @buyer4 = (SELECT id FROM member WHERE email = 'buyer4@jarvis.shop');
SET @buyer5 = (SELECT id FROM member WHERE email = 'buyer5@jarvis.shop');

-- ── product_change_logs (I-15 — 품절 신호 STOCK newValue '0' 포함) ──
INSERT INTO product_change_logs (product_id, change_type, old_value, new_value, created_at)
SELECT * FROM (
    SELECT 9434825492 AS product_id, 'PRICE' AS change_type, '190000' AS old_value, '180120' AS new_value, NOW() - INTERVAL 12 DAY AS created_at
    UNION ALL SELECT 9349985278, 'STOCK', '100', '0', NOW() - INTERVAL 7 DAY
    UNION ALL SELECT 9349985278, 'STOCK', '0', '100', NOW() - INTERVAL 5 DAY
    UNION ALL SELECT 9236338700, 'STATUS', 'ON_SALE', 'HIDDEN', NOW() - INTERVAL 6 DAY
    UNION ALL SELECT 9236338700, 'STATUS', 'HIDDEN', 'ON_SALE', NOW() - INTERVAL 4 DAY
) t
WHERE NOT EXISTS (SELECT 1 FROM product_change_logs
                  WHERE product_id = 9434825492 AND change_type = 'PRICE' AND new_value = '180120');

-- ── account_event_logs (I-8·I-16 — buyer3~5는 마지막 로그인 45일+ 전 = 이탈) ──
INSERT INTO account_event_logs (member_id, event_type, ip_address, created_at)
SELECT * FROM (
    SELECT @buyer1 AS member_id, 'LOGIN_SUCCESS' AS event_type, '203.0.113.10' AS ip_address, NOW() - INTERVAL 2 DAY AS created_at
    UNION ALL SELECT @buyer1, 'LOGIN_SUCCESS', '203.0.113.10', NOW() - INTERVAL 10 DAY
    UNION ALL SELECT @buyer2, 'LOGIN_SUCCESS', '198.51.100.24', NOW() - INTERVAL 1 DAY
    UNION ALL SELECT @buyer2, 'LOGIN_SUCCESS', '198.51.100.24', NOW() - INTERVAL 8 DAY
    UNION ALL SELECT @buyer3, 'LOGIN_SUCCESS', '192.0.2.55', NOW() - INTERVAL 45 DAY
    UNION ALL SELECT @buyer4, 'LOGIN_SUCCESS', '192.0.2.77', NOW() - INTERVAL 50 DAY
    UNION ALL SELECT @buyer5, 'LOGIN_SUCCESS', '192.0.2.88', NOW() - INTERVAL 60 DAY
    -- 무차별 대입 패턴 데모 (I-8 groupBy=ip): 같은 IP 연속 실패
    UNION ALL SELECT NULL, 'LOGIN_FAIL', '10.77.0.99', NOW() - INTERVAL 1 DAY
    UNION ALL SELECT NULL, 'LOGIN_FAIL', '10.77.0.99', NOW() - INTERVAL 1 DAY - INTERVAL 1 MINUTE
    UNION ALL SELECT NULL, 'LOGIN_FAIL', '10.77.0.99', NOW() - INTERVAL 1 DAY - INTERVAL 2 MINUTE
    UNION ALL SELECT NULL, 'LOGIN_FAIL', '10.77.0.99', NOW() - INTERVAL 1 DAY - INTERVAL 3 MINUTE
    UNION ALL SELECT @buyer1, 'LOGIN_FAIL', '10.77.0.99', NOW() - INTERVAL 1 DAY - INTERVAL 4 MINUTE
    UNION ALL SELECT NULL, 'LOGIN_FAIL', '10.77.0.99', NOW() - INTERVAL 1 DAY - INTERVAL 5 MINUTE
) t
WHERE NOT EXISTS (SELECT 1 FROM account_event_logs WHERE ip_address = '10.77.0.99');

-- ── behavior_events 더미 (S-1 조회수·담김수, I-7 퍼널, I-16 preChurnSignals) ──
-- 스코프: seller2 상품(7종) 중심, 최근 30일 분산. member/guest 혼재(FK 미설정 — 02 D31).

-- product_view 360건
INSERT IGNORE INTO behavior_events (member_id, guest_id, session_key, client_event_id, event_type,
                                    product_id, properties, created_at)
SELECT CASE seq MOD 6
           WHEN 0 THEN @buyer1 WHEN 1 THEN @buyer2 WHEN 2 THEN @buyer3
           WHEN 3 THEN @buyer4 WHEN 4 THEN @buyer5 ELSE NULL END,
       CASE WHEN seq MOD 6 = 5 THEN CONCAT('a0000000-0000-4000-8000-', LPAD(seq, 12, '0')) END,
       CONCAT('seed-an-session-', seq MOD 40),
       CONCAT('seed-an-view-', LPAD(seq, 6, '0')),
       'product_view',
       ELT(1 + (seq MOD 7), 2376177306, 9233450819, 9236338700, 9349985278, 9381716646, 9434825492, 9470646527),
       NULL,
       NOW() - INTERVAL (seq MOD 30) DAY - INTERVAL (seq MOD 23) HOUR
FROM seq_1_to_360;

-- 이탈 회원 preChurnSignals용 — 마지막 로그인 직전 30일 구간(45~75일 전)의 행동
INSERT IGNORE INTO behavior_events (member_id, guest_id, session_key, client_event_id, event_type,
                                    product_id, properties, created_at)
SELECT CASE seq MOD 3 WHEN 0 THEN @buyer3 WHEN 1 THEN @buyer4 ELSE @buyer5 END,
       NULL,
       CONCAT('seed-an-churn-s', seq MOD 10),
       CONCAT('seed-an-churn-', LPAD(seq, 6, '0')),
       CASE seq MOD 3 WHEN 0 THEN 'product_view' WHEN 1 THEN 'add_to_cart' ELSE 'search' END,
       CASE WHEN seq MOD 3 = 2 THEN NULL
            ELSE ELT(1 + (seq MOD 7), 2376177306, 9233450819, 9236338700, 9349985278, 9381716646, 9434825492, 9470646527) END,
       CASE WHEN seq MOD 3 = 2 THEN '{"keyword":"모니터"}' END,
       NOW() - INTERVAL (46 + (seq MOD 28)) DAY
FROM seq_1_to_60;

-- add_to_cart 90건
INSERT IGNORE INTO behavior_events (member_id, guest_id, session_key, client_event_id, event_type,
                                    product_id, properties, created_at)
SELECT CASE seq MOD 5
           WHEN 0 THEN @buyer1 WHEN 1 THEN @buyer2 WHEN 2 THEN @buyer3
           WHEN 3 THEN @buyer4 ELSE @buyer5 END,
       NULL,
       CONCAT('seed-an-session-', seq MOD 40),
       CONCAT('seed-an-cart-', LPAD(seq, 6, '0')),
       'add_to_cart',
       ELT(1 + (seq MOD 7), 2376177306, 9233450819, 9236338700, 9349985278, 9381716646, 9434825492, 9470646527),
       NULL,
       NOW() - INTERVAL (seq MOD 30) DAY - INTERVAL (seq MOD 19) HOUR
FROM seq_1_to_90;

-- checkout_start 24건 (I-7 3단 — properties.productIds에 seller2 상품 포함)
INSERT IGNORE INTO behavior_events (member_id, guest_id, session_key, client_event_id, event_type,
                                    product_id, properties, created_at)
SELECT CASE seq MOD 4
           WHEN 0 THEN @buyer1 WHEN 1 THEN @buyer2 WHEN 2 THEN @buyer3 ELSE @buyer4 END,
       NULL,
       CONCAT('seed-an-session-', seq MOD 40),
       CONCAT('seed-an-checkout-', LPAD(seq, 6, '0')),
       'checkout_start',
       NULL,
       CONCAT('{"productIds":[', ELT(1 + (seq MOD 7), 2376177306, 9233450819, 9236338700, 9349985278, 9381716646, 9434825492, 9470646527), ']}'),
       NOW() - INTERVAL (seq MOD 30) DAY - INTERVAL (seq MOD 13) HOUR
FROM seq_1_to_24;

-- purchase_complete 8건 (시드 주문과 짝 — 퍼널 4단 정본은 order_item이지만 이벤트도 깔아둠)
INSERT IGNORE INTO behavior_events (member_id, guest_id, session_key, client_event_id, event_type,
                                    product_id, properties, created_at)
SELECT CASE seq MOD 4
           WHEN 0 THEN @buyer1 WHEN 1 THEN @buyer2 WHEN 2 THEN @buyer3 ELSE @buyer4 END,
       NULL,
       CONCAT('seed-an-session-', seq MOD 40),
       CONCAT('seed-an-purchase-', LPAD(seq, 6, '0')),
       'purchase_complete',
       NULL,
       CONCAT('{"orderId":', 9000 + seq, ',"amount":', 45000 + seq * 10000, '}'),
       NOW() - INTERVAL (seq * 4 MOD 30) DAY
FROM seq_1_to_8;

-- search 30건 (S-1엔 안 쓰이지만 이벤트 분포 현실화)
INSERT IGNORE INTO behavior_events (member_id, guest_id, session_key, client_event_id, event_type,
                                    product_id, properties, created_at)
SELECT CASE seq MOD 3 WHEN 0 THEN @buyer1 WHEN 1 THEN @buyer2 ELSE NULL END,
       CASE WHEN seq MOD 3 = 2 THEN CONCAT('b0000000-0000-4000-8000-', LPAD(seq, 12, '0')) END,
       CONCAT('seed-an-session-', seq MOD 40),
       CONCAT('seed-an-search-', LPAD(seq, 6, '0')),
       'search',
       NULL,
       '{"keyword":"가전"}',
       NOW() - INTERVAL (seq MOD 30) DAY - INTERVAL (seq MOD 11) HOUR
FROM seq_1_to_30;
