-- Phase 6 시드 — 판매자 대시보드/분석 콜백 데모 데이터 (06 Phase 6: summary가 0이 아닌 지표)
-- 구성: 판매자 2호(사운드빔 연결) + 데모 구매자 5명 + 브랜드7(홈트론) 중심 주문·전이 로그
--       + behavior_events/product_change_logs/account_event_logs 더미 (02 §5 "판매자 브랜드에 더미")
-- 로그인: buyer1~5@jarvis.shop, seller2@jarvis.shop 모두 / seller1234 (BCrypt 해시 재사용 — 로컬 전용)
-- 날짜는 NOW() 기준 상대값 — 언제 실행해도 "최근 30일" 데모가 성립. 재실행 무해(INSERT IGNORE/NOT EXISTS).
-- 사전 조건: seed-phase1.sql(판매자 1호), seed-phase2.sql(카탈로그) 선적용.
-- 적용: docker exec -i jarvis-mariadb mariadb -ujarvis -pjarvis-local jarvis < scripts/seed-phase6.sql

-- ── 판매자 2호: 사운드빔(브랜드 8) 연결 (02 §5 "판매자 계정 2~3개") ──
INSERT INTO member (email, password, nickname, role, gender, birth_date,
                    agreed_terms_at, agreed_privacy_at, created_at)
SELECT 'seller2@jarvis.shop',
       '$2a$10$ofCmr3m/dvJuwlJD7625ZeSPWxy7tzXr9Dh9rLzEVXMlMj2MB9xCi',
       'jarvis-seller2', 'SELLER', 'FEMALE', '1992-05-15',
       NOW(), NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM member WHERE email = 'seller2@jarvis.shop');

UPDATE brand SET seller_id = (SELECT id FROM member WHERE email = 'seller2@jarvis.shop')
WHERE id = 8 AND seller_id IS NULL;

-- ── 데모 구매자 5명 (I-16 이탈 코호트: buyer1·2 활성 / buyer3·4·5 이탈) ──
INSERT INTO member (email, password, nickname, role, gender, birth_date,
                    agreed_terms_at, agreed_privacy_at, created_at)
SELECT * FROM (
    SELECT 'buyer1@jarvis.shop' AS email, '$2a$10$ofCmr3m/dvJuwlJD7625ZeSPWxy7tzXr9Dh9rLzEVXMlMj2MB9xCi' AS password,
           'buyer-active-1' AS nickname, 'USER' AS role, 'FEMALE' AS gender, DATE '1995-03-01' AS birth_date,
           NOW() AS a, NOW() AS b, NOW() - INTERVAL 70 DAY AS c
    UNION ALL SELECT 'buyer2@jarvis.shop', '$2a$10$ofCmr3m/dvJuwlJD7625ZeSPWxy7tzXr9Dh9rLzEVXMlMj2MB9xCi',
           'buyer-active-2', 'USER', 'MALE', DATE '1988-11-20', NOW(), NOW(), NOW() - INTERVAL 65 DAY
    UNION ALL SELECT 'buyer3@jarvis.shop', '$2a$10$ofCmr3m/dvJuwlJD7625ZeSPWxy7tzXr9Dh9rLzEVXMlMj2MB9xCi',
           'buyer-churned-1', 'USER', 'FEMALE', DATE '1999-07-07', NOW(), NOW(), NOW() - INTERVAL 90 DAY
    UNION ALL SELECT 'buyer4@jarvis.shop', '$2a$10$ofCmr3m/dvJuwlJD7625ZeSPWxy7tzXr9Dh9rLzEVXMlMj2MB9xCi',
           'buyer-churned-2', 'USER', 'MALE', DATE '1985-01-30', NOW(), NOW(), NOW() - INTERVAL 95 DAY
    UNION ALL SELECT 'buyer5@jarvis.shop', '$2a$10$ofCmr3m/dvJuwlJD7625ZeSPWxy7tzXr9Dh9rLzEVXMlMj2MB9xCi',
           'buyer-churned-3', 'USER', 'FEMALE', DATE '1993-09-12', NOW(), NOW(), NOW() - INTERVAL 100 DAY
) t
WHERE NOT EXISTS (SELECT 1 FROM member WHERE email = t.email);

SET @buyer1 = (SELECT id FROM member WHERE email = 'buyer1@jarvis.shop');
SET @buyer2 = (SELECT id FROM member WHERE email = 'buyer2@jarvis.shop');
SET @buyer3 = (SELECT id FROM member WHERE email = 'buyer3@jarvis.shop');
SET @buyer4 = (SELECT id FROM member WHERE email = 'buyer4@jarvis.shop');
SET @buyer5 = (SELECT id FROM member WHERE email = 'buyer5@jarvis.shop');

-- ── 주문 스냅샷 (브랜드7 중심 — S-1/S-2/I-6. 3일 전 428,400원 스파이크 = I-6 isAnomaly 데모) ──
-- 고정 id 9001~9009 — 런타임 주문과 충돌 방지. total = Σ(item price×qty) (02 D26④).
INSERT IGNORE INTO orders (id, member_id, status, payment_method, total_amount, recipient, phone,
                           zip_code, address1, address2, delivery_request, paid_at, created_at, updated_at) VALUES
(9001, @buyer1, 'PAID', 'MOCK_CARD', 96800,  '김활동', '010-1111-0001', '04524', '서울 중구 세종대로 110', NULL, '문 앞에 놓아주세요', NOW() - INTERVAL 32 DAY, NOW() - INTERVAL 32 DAY, NOW() - INTERVAL 32 DAY),
(9002, @buyer2, 'PAID', 'MOCK_CARD', 124200, '이구매', '010-1111-0002', '06236', '서울 강남구 테헤란로 123', '101동 202호', NULL, NOW() - INTERVAL 25 DAY, NOW() - INTERVAL 25 DAY, NOW() - INTERVAL 25 DAY),
(9003, @buyer3, 'PAID', 'MOCK_CARD', 159000, '박이탈', '010-1111-0003', '48058', '부산 해운대구 센텀로 45', NULL, NULL, NOW() - INTERVAL 20 DAY, NOW() - INTERVAL 20 DAY, NOW() - INTERVAL 20 DAY),
(9004, @buyer4, 'PAID', 'MOCK_CARD', 86400,  '최반품', '010-1111-0004', '34126', '대전 유성구 대학로 99', NULL, '경비실 맡겨주세요', NOW() - INTERVAL 14 DAY, NOW() - INTERVAL 14 DAY, NOW() - INTERVAL 14 DAY),
(9005, @buyer5, 'CANCELLED', 'MOCK_CARD', 89000, '정취소', '010-1111-0005', '61945', '광주 서구 상무대로 312', NULL, NULL, NOW() - INTERVAL 10 DAY, NOW() - INTERVAL 10 DAY, NOW() - INTERVAL 10 DAY),
(9006, @buyer1, 'PAID', 'MOCK_CARD', 428400, '김활동', '010-1111-0001', '04524', '서울 중구 세종대로 110', NULL, NULL, NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 3 DAY),
(9007, @buyer2, 'PAID', 'MOCK_CARD', 96800,  '이구매', '010-1111-0002', '06236', '서울 강남구 테헤란로 123', '101동 202호', NULL, NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 2 DAY),
(9008, @buyer3, 'PAID', 'MOCK_CARD', 45000,  '박이탈', '010-1111-0003', '48058', '부산 해운대구 센텀로 45', NULL, NULL, NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 1 DAY),
(9009, @buyer4, 'PAID', 'MOCK_CARD', 103200, '최반품', '010-1111-0004', '34126', '대전 유성구 대학로 99', NULL, NULL, NOW() - INTERVAL 5 DAY, NOW() - INTERVAL 5 DAY, NOW() - INTERVAL 5 DAY);

-- 아이템 스냅샷 — 가격은 seed-phase2 판매가(+옵션 extra) 그대로 (02 D1·D37)
INSERT IGNORE INTO order_item (id, order_id, product_id, product_name, option_name, price, original_price,
                               quantity, status, status_changed_at, created_at, updated_at) VALUES
(9101, 9001, 37, '스마트 에어프라이어 5.5L', '블랙', 96800, 129000, 1, 'CONFIRMED', NOW() - INTERVAL 26 DAY, NOW() - INTERVAL 32 DAY, NOW() - INTERVAL 26 DAY),
(9102, 9002, 42, '무선 핸디 청소기', NULL, 79200, 99000, 1, 'DELIVERED', NOW() - INTERVAL 22 DAY, NOW() - INTERVAL 25 DAY, NOW() - INTERVAL 22 DAY),
(9103, 9002, 40, '전기 티포트 1.2L', NULL, 45000, 45000, 1, 'DELIVERED', NOW() - INTERVAL 22 DAY, NOW() - INTERVAL 25 DAY, NOW() - INTERVAL 22 DAY),
(9104, 9003, 43, '공기청정기 12평형', NULL, 159000, 159000, 1, 'DELIVERED', NOW() - INTERVAL 17 DAY, NOW() - INTERVAL 20 DAY, NOW() - INTERVAL 17 DAY),
(9105, 9004, 44, '스팀 다리미', '퍼플', 39200, 49000, 1, 'RETURNED', NOW() - INTERVAL 8 DAY, NOW() - INTERVAL 14 DAY, NOW() - INTERVAL 8 DAY),
(9106, 9004, 39, '미니 전기밥솥 3인용', '화이트', 47200, 59000, 1, 'DELIVERED', NOW() - INTERVAL 11 DAY, NOW() - INTERVAL 14 DAY, NOW() - INTERVAL 11 DAY),
(9107, 9005, 38, '초고속 블렌더 1.8L', NULL, 89000, 89000, 1, 'CANCELLED', NOW() - INTERVAL 9 DAY, NOW() - INTERVAL 10 DAY, NOW() - INTERVAL 9 DAY),
(9108, 9006, 43, '공기청정기 12평형', NULL, 159000, 159000, 2, 'SHIPPING', NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 1 DAY),
(9109, 9006, 41, '인덕션 1구 포터블', NULL, 55200, 69000, 2, 'SHIPPING', NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 1 DAY),
(9110, 9007, 37, '스마트 에어프라이어 5.5L', '블랙', 96800, 129000, 1, 'SHIPPING', NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 1 DAY),
(9111, 9008, 40, '전기 티포트 1.2L', NULL, 45000, 45000, 1, 'ORDERED', NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 1 DAY),
(9112, 9009, 46, '노이즈캔슬링 무선 이어폰', '블랙', 103200, 129000, 1, 'DELIVERED', NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 5 DAY, NOW() - INTERVAL 2 DAY);

-- ── order_status_logs (I-14 — 01 §6.5 기록 지점 규칙 준수: ORDERED/*_REQUESTED/CONFIRMED 미기록) ──
INSERT INTO order_status_logs (order_id, from_status, to_status, actor_type, reason, created_at)
SELECT * FROM (
    SELECT 9001 AS order_id, 'PENDING' AS from_status, 'PAID' AS to_status, 'SYSTEM' AS actor_type, CAST(NULL AS CHAR(200)) AS reason, NOW() - INTERVAL 32 DAY AS created_at
    UNION ALL SELECT 9001, 'ORDERED', 'SHIPPING', 'SYSTEM', NULL, NOW() - INTERVAL 31 DAY
    UNION ALL SELECT 9001, 'SHIPPING', 'DELIVERED', 'SYSTEM', NULL, NOW() - INTERVAL 29 DAY
    UNION ALL SELECT 9002, 'PENDING', 'PAID', 'SYSTEM', NULL, NOW() - INTERVAL 25 DAY
    UNION ALL SELECT 9002, 'ORDERED', 'SHIPPING', 'SYSTEM', NULL, NOW() - INTERVAL 24 DAY
    UNION ALL SELECT 9002, 'SHIPPING', 'DELIVERED', 'SYSTEM', NULL, NOW() - INTERVAL 22 DAY
    UNION ALL SELECT 9003, 'PENDING', 'PAID', 'SYSTEM', NULL, NOW() - INTERVAL 20 DAY
    UNION ALL SELECT 9003, 'ORDERED', 'SHIPPING', 'SYSTEM', NULL, NOW() - INTERVAL 19 DAY
    UNION ALL SELECT 9003, 'SHIPPING', 'DELIVERED', 'SYSTEM', NULL, NOW() - INTERVAL 17 DAY
    UNION ALL SELECT 9004, 'PENDING', 'PAID', 'SYSTEM', NULL, NOW() - INTERVAL 14 DAY
    UNION ALL SELECT 9004, 'ORDERED', 'SHIPPING', 'SYSTEM', NULL, NOW() - INTERVAL 13 DAY
    UNION ALL SELECT 9004, 'SHIPPING', 'DELIVERED', 'SYSTEM', NULL, NOW() - INTERVAL 11 DAY
    UNION ALL SELECT 9004, 'RETURN_REQUESTED', 'RETURNED', 'USER', '단순 변심', NOW() - INTERVAL 8 DAY
    UNION ALL SELECT 9005, 'PENDING', 'PAID', 'SYSTEM', NULL, NOW() - INTERVAL 10 DAY
    UNION ALL SELECT 9005, 'CANCEL_REQUESTED', 'CANCELLED', 'USER', '주문 실수', NOW() - INTERVAL 9 DAY
    UNION ALL SELECT 9006, 'PENDING', 'PAID', 'SYSTEM', NULL, NOW() - INTERVAL 3 DAY
    UNION ALL SELECT 9006, 'ORDERED', 'SHIPPING', 'SYSTEM', NULL, NOW() - INTERVAL 1 DAY
    UNION ALL SELECT 9007, 'PENDING', 'PAID', 'SYSTEM', NULL, NOW() - INTERVAL 2 DAY
    UNION ALL SELECT 9007, 'ORDERED', 'SHIPPING', 'SYSTEM', NULL, NOW() - INTERVAL 1 DAY
    UNION ALL SELECT 9008, 'PENDING', 'PAID', 'SYSTEM', NULL, NOW() - INTERVAL 1 DAY
    UNION ALL SELECT 9009, 'PENDING', 'PAID', 'SYSTEM', NULL, NOW() - INTERVAL 5 DAY
    UNION ALL SELECT 9009, 'ORDERED', 'SHIPPING', 'SYSTEM', NULL, NOW() - INTERVAL 4 DAY
    UNION ALL SELECT 9009, 'SHIPPING', 'DELIVERED', 'SYSTEM', NULL, NOW() - INTERVAL 2 DAY
) t
WHERE NOT EXISTS (SELECT 1 FROM order_status_logs WHERE order_id = 9001);

-- ── product_change_logs (I-15 — 품절 신호 STOCK newValue '0' 포함) ──
INSERT INTO product_change_logs (product_id, change_type, old_value, new_value, created_at)
SELECT * FROM (
    SELECT 37 AS product_id, 'PRICE' AS change_type, '110000' AS old_value, '96800' AS new_value, NOW() - INTERVAL 12 DAY AS created_at
    UNION ALL SELECT 44, 'STOCK', '100', '0', NOW() - INTERVAL 7 DAY
    UNION ALL SELECT 44, 'STOCK', '0', '100', NOW() - INTERVAL 5 DAY
    UNION ALL SELECT 45, 'STATUS', 'ON_SALE', 'HIDDEN', NOW() - INTERVAL 6 DAY
    UNION ALL SELECT 45, 'STATUS', 'HIDDEN', 'ON_SALE', NOW() - INTERVAL 4 DAY
) t
WHERE NOT EXISTS (SELECT 1 FROM product_change_logs
                  WHERE product_id = 37 AND change_type = 'PRICE' AND new_value = '96800');

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
-- MariaDB SEQUENCE 엔진(seq_1_to_N)으로 생성. client_event_id 고정 접두라 재실행 무해(INSERT IGNORE + UNIQUE).
-- 스코프: 브랜드7 상품(37~45) 중심, 최근 30일 분산. member/guest 혼재(FK 미설정 — 02 D31).

-- product_view 360건 (상품별 40건 수준)
INSERT IGNORE INTO behavior_events (member_id, guest_id, session_key, client_event_id, event_type,
                                    product_id, properties, created_at)
SELECT CASE seq MOD 6
           WHEN 0 THEN @buyer1 WHEN 1 THEN @buyer2 WHEN 2 THEN @buyer3
           WHEN 3 THEN @buyer4 WHEN 4 THEN @buyer5 ELSE NULL END,
       CASE WHEN seq MOD 6 = 5 THEN CONCAT('a0000000-0000-4000-8000-', LPAD(seq, 12, '0')) END,
       CONCAT('seed-p6-session-', seq MOD 40),
       CONCAT('seed-p6-view-', LPAD(seq, 6, '0')),
       'product_view',
       37 + (seq MOD 9),
       NULL,
       NOW() - INTERVAL (seq MOD 30) DAY - INTERVAL (seq MOD 23) HOUR
FROM seq_1_to_360;

-- 이탈 회원 preChurnSignals용 — 마지막 로그인 직전 30일 구간(45~75일 전)의 행동
INSERT IGNORE INTO behavior_events (member_id, guest_id, session_key, client_event_id, event_type,
                                    product_id, properties, created_at)
SELECT CASE seq MOD 3 WHEN 0 THEN @buyer3 WHEN 1 THEN @buyer4 ELSE @buyer5 END,
       NULL,
       CONCAT('seed-p6-churn-s', seq MOD 10),
       CONCAT('seed-p6-churn-', LPAD(seq, 6, '0')),
       CASE seq MOD 3 WHEN 0 THEN 'product_view' WHEN 1 THEN 'add_to_cart' ELSE 'search' END,
       CASE WHEN seq MOD 3 = 2 THEN NULL ELSE 37 + (seq MOD 9) END,
       CASE WHEN seq MOD 3 = 2 THEN '{"keyword":"에어프라이어"}' END,
       NOW() - INTERVAL (46 + (seq MOD 28)) DAY
FROM seq_1_to_60;

-- add_to_cart 90건
INSERT IGNORE INTO behavior_events (member_id, guest_id, session_key, client_event_id, event_type,
                                    product_id, properties, created_at)
SELECT CASE seq MOD 5
           WHEN 0 THEN @buyer1 WHEN 1 THEN @buyer2 WHEN 2 THEN @buyer3
           WHEN 3 THEN @buyer4 ELSE @buyer5 END,
       NULL,
       CONCAT('seed-p6-session-', seq MOD 40),
       CONCAT('seed-p6-cart-', LPAD(seq, 6, '0')),
       'add_to_cart',
       37 + (seq MOD 9),
       NULL,
       NOW() - INTERVAL (seq MOD 30) DAY - INTERVAL (seq MOD 19) HOUR
FROM seq_1_to_90;

-- checkout_start 24건 (I-7 3단 — properties.productIds에 브랜드7 상품 포함)
INSERT IGNORE INTO behavior_events (member_id, guest_id, session_key, client_event_id, event_type,
                                    product_id, properties, created_at)
SELECT CASE seq MOD 4
           WHEN 0 THEN @buyer1 WHEN 1 THEN @buyer2 WHEN 2 THEN @buyer3 ELSE @buyer4 END,
       NULL,
       CONCAT('seed-p6-session-', seq MOD 40),
       CONCAT('seed-p6-checkout-', LPAD(seq, 6, '0')),
       'checkout_start',
       NULL,
       CONCAT('{"productIds":[', 37 + (seq MOD 9), ']}'),
       NOW() - INTERVAL (seq MOD 30) DAY - INTERVAL (seq MOD 13) HOUR
FROM seq_1_to_24;

-- purchase_complete 8건 (시드 주문과 짝 — 퍼널 4단 정본은 order_item이지만 이벤트도 깔아둠)
INSERT IGNORE INTO behavior_events (member_id, guest_id, session_key, client_event_id, event_type,
                                    product_id, properties, created_at)
SELECT CASE seq MOD 4
           WHEN 0 THEN @buyer1 WHEN 1 THEN @buyer2 WHEN 2 THEN @buyer3 ELSE @buyer4 END,
       NULL,
       CONCAT('seed-p6-session-', seq MOD 40),
       CONCAT('seed-p6-purchase-', LPAD(seq, 6, '0')),
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
       CONCAT('seed-p6-session-', seq MOD 40),
       CONCAT('seed-p6-search-', LPAD(seq, 6, '0')),
       'search',
       NULL,
       '{"keyword":"가전"}',
       NOW() - INTERVAL (seq MOD 30) DAY - INTERVAL (seq MOD 11) HOUR
FROM seq_1_to_30;
