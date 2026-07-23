-- 시드 — 거래 데모 (판매자 브랜드 + 주문/아이템/상태로그 + 문의)
-- 기준: seed-catalog.sql(sample-100)의 실물 상품. seller2가 데모 리셀러 브랜드로 가전/디지털 7종을 소유.
--   스키마 제약 uk_brand_seller: 판매자당 브랜드 1개. sample-100은 브랜드당 ~1상품이라
--   seller2 전용 브랜드에 7종을 편입(리셀러 모델)해 대시보드 지표를 채운다.
--   편입 7종(원 제조사 표기는 상품명에 남고, brand만 판매자 브랜드로 바뀜):
--     2376177306 더함 32" TV(353000) | 9233450819 LG 32U631A(440000) | 9236338700 MSI(241000)
--     9349985278 삼성 55" TV(767700, 옵션 '단일옵션') | 9381716646 LG 27U411A(188000)
--     9434825492 LG 27U411A(180120) | 9470646527 삼성 G5(323000)
-- 날짜는 NOW() 기준 상대값 — 언제 실행해도 "최근 30일" 데모 성립. 재실행 무해(INSERT IGNORE/NOT EXISTS).
-- 사전: seed-accounts.sql(계정) + seed-catalog.sql(카탈로그) 선적용.
-- 적용: docker exec -i jarvis-mariadb mariadb -ujarvis -pjarvis-local jarvis < scripts/seed-commerce-demo.sql

-- ── seller2 데모 리셀러 브랜드 생성 + 7종 편입 ──
INSERT INTO brand (seller_id, name, logo_url, description, created_at, updated_at)
SELECT (SELECT id FROM member WHERE email = 'seller2@jarvis.shop'),
       '자비스일렉트로닉스', NULL, 'JARVIS 데모 리셀러 — 모니터/TV 라인업', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM brand WHERE name = '자비스일렉트로닉스');

UPDATE product SET brand_id = (SELECT id FROM brand WHERE name = '자비스일렉트로닉스')
WHERE id IN (2376177306, 9233450819, 9236338700, 9349985278, 9381716646, 9434825492, 9470646527);

SET @buyer1 = (SELECT id FROM member WHERE email = 'buyer1@jarvis.shop');
SET @buyer2 = (SELECT id FROM member WHERE email = 'buyer2@jarvis.shop');
SET @buyer3 = (SELECT id FROM member WHERE email = 'buyer3@jarvis.shop');
SET @buyer4 = (SELECT id FROM member WHERE email = 'buyer4@jarvis.shop');
SET @buyer5 = (SELECT id FROM member WHERE email = 'buyer5@jarvis.shop');

-- ── 주문 스냅샷 (seller2 상품 중심 — S-1/S-2/I-6. 3일 전 1,207,700원 스파이크 = I-6 isAnomaly 데모) ──
-- 고정 id 9001~9009 — 런타임 주문과 충돌 방지. total = Σ(item price×qty) (02 D26④).
INSERT IGNORE INTO orders (id, member_id, status, payment_method, total_amount, recipient, phone,
                           zip_code, address1, address2, delivery_request, paid_at, created_at, updated_at) VALUES
(9001, @buyer1, 'PAID', 'MOCK_CARD', 188000,  '김활동', '010-1111-0001', '04524', '서울 중구 세종대로 110', NULL, '문 앞에 놓아주세요', NOW() - INTERVAL 32 DAY, NOW() - INTERVAL 32 DAY, NOW() - INTERVAL 32 DAY),
(9002, @buyer2, 'PAID', 'MOCK_CARD', 421120,  '이구매', '010-1111-0002', '06236', '서울 강남구 테헤란로 123', '101동 202호', NULL, NOW() - INTERVAL 25 DAY, NOW() - INTERVAL 25 DAY, NOW() - INTERVAL 25 DAY),
(9003, @buyer3, 'PAID', 'MOCK_CARD', 323000,  '박이탈', '010-1111-0003', '48058', '부산 해운대구 센텀로 45', NULL, NULL, NOW() - INTERVAL 20 DAY, NOW() - INTERVAL 20 DAY, NOW() - INTERVAL 20 DAY),
(9004, @buyer4, 'PAID', 'MOCK_CARD', 368120,  '최반품', '010-1111-0004', '34126', '대전 유성구 대학로 99', NULL, '경비실 맡겨주세요', NOW() - INTERVAL 14 DAY, NOW() - INTERVAL 14 DAY, NOW() - INTERVAL 14 DAY),
(9005, @buyer5, 'CANCELLED', 'MOCK_CARD', 241000, '정취소', '010-1111-0005', '61945', '광주 서구 상무대로 312', NULL, NULL, NOW() - INTERVAL 10 DAY, NOW() - INTERVAL 10 DAY, NOW() - INTERVAL 10 DAY),
(9006, @buyer1, 'PAID', 'MOCK_CARD', 1207700, '김활동', '010-1111-0001', '04524', '서울 중구 세종대로 110', NULL, NULL, NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 3 DAY),
(9007, @buyer2, 'PAID', 'MOCK_CARD', 188000,  '이구매', '010-1111-0002', '06236', '서울 강남구 테헤란로 123', '101동 202호', NULL, NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 2 DAY),
(9008, @buyer3, 'PAID', 'MOCK_CARD', 180120,  '박이탈', '010-1111-0003', '48058', '부산 해운대구 센텀로 45', NULL, NULL, NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 1 DAY),
(9009, @buyer4, 'PAID', 'MOCK_CARD', 323000,  '최반품', '010-1111-0004', '34126', '대전 유성구 대학로 99', NULL, NULL, NOW() - INTERVAL 5 DAY, NOW() - INTERVAL 5 DAY, NOW() - INTERVAL 5 DAY);

-- 아이템 스냅샷 — 가격은 catalog 판매가/정가 그대로 (02 D1·D37). option_name은 실제 옵션(삼성 55"TV만 단일옵션).
INSERT IGNORE INTO order_item (id, order_id, product_id, product_name, option_name, price, original_price,
                               quantity, status, status_changed_at, created_at, updated_at) VALUES
(9101, 9001, 9381716646, 'LG전자 27U411A 모니터 케이제이샵', NULL, 188000, 188000, 1, 'CONFIRMED', NOW() - INTERVAL 26 DAY, NOW() - INTERVAL 32 DAY, NOW() - INTERVAL 26 DAY),
(9102, 9002, 9236338700, 'MSI PRO MP273QW E14 게이밍 WQHD 144 스피커내장 화이트 무결점', NULL, 241000, 241000, 1, 'DELIVERED', NOW() - INTERVAL 22 DAY, NOW() - INTERVAL 25 DAY, NOW() - INTERVAL 22 DAY),
(9103, 9002, 9434825492, 'LG전자 27U411A 68~69cm', NULL, 180120, 190000, 1, 'DELIVERED', NOW() - INTERVAL 22 DAY, NOW() - INTERVAL 25 DAY, NOW() - INTERVAL 22 DAY),
(9104, 9003, 9470646527, '삼성전자 오디세이 G5 G55C S32CG550 80cm(32인치) IPS 165Hz QHD 게이밍 모니터 LS32CG550EKXKR', NULL, 323000, 323000, 1, 'DELIVERED', NOW() - INTERVAL 17 DAY, NOW() - INTERVAL 20 DAY, NOW() - INTERVAL 17 DAY),
(9105, 9004, 9434825492, 'LG전자 27U411A 68~69cm', NULL, 180120, 190000, 1, 'RETURNED', NOW() - INTERVAL 8 DAY, NOW() - INTERVAL 14 DAY, NOW() - INTERVAL 8 DAY),
(9106, 9004, 9381716646, 'LG전자 27U411A 모니터 케이제이샵', NULL, 188000, 188000, 1, 'DELIVERED', NOW() - INTERVAL 11 DAY, NOW() - INTERVAL 14 DAY, NOW() - INTERVAL 11 DAY),
(9107, 9005, 9236338700, 'MSI PRO MP273QW E14 게이밍 WQHD 144 스피커내장 화이트 무결점', NULL, 241000, 241000, 1, 'CANCELLED', NOW() - INTERVAL 9 DAY, NOW() - INTERVAL 10 DAY, NOW() - INTERVAL 9 DAY),
(9108, 9006, 9349985278, '[삼성전자] 4K UHD TV LH55BEFHLGFXKR 스탠드형-후[35590284', '단일옵션', 767700, 809800, 1, 'SHIPPING', NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 1 DAY),
(9109, 9006, 9233450819, 'LG PC 모니터 80cm 32U631A 2560 x 1440 QHD /온오프', NULL, 440000, 440000, 1, 'SHIPPING', NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 1 DAY),
(9110, 9007, 9381716646, 'LG전자 27U411A 모니터 케이제이샵', NULL, 188000, 188000, 1, 'SHIPPING', NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 1 DAY),
(9111, 9008, 9434825492, 'LG전자 27U411A 68~69cm', NULL, 180120, 190000, 1, 'ORDERED', NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 1 DAY),
(9112, 9009, 9470646527, '삼성전자 오디세이 G5 G55C S32CG550 80cm(32인치) IPS 165Hz QHD 게이밍 모니터 LS32CG550EKXKR', NULL, 323000, 323000, 1, 'DELIVERED', NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 5 DAY, NOW() - INTERVAL 2 DAY);

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

-- ── 문의 (M-9 데모) — user@jarvis.shop(가입 API로 먼저 생성) 대상. 없으면 아무것도 안 넣음 ──
INSERT INTO inquiry (member_id, title, content, status, answer, answered_by, answered_at, created_at)
SELECT m.id,
       '배송 지연 문의',
       '지난주에 주문한 상품이 아직 배송 준비 중입니다. 언제쯤 받을 수 있을까요?',
       'DONE',
       '안녕하세요, 고객님. 확인 결과 물류센터 출고가 지연되어 불편을 드렸습니다. 오늘 출고 완료되었으며 1~2일 내 수령 가능합니다.',
       (SELECT id FROM member WHERE email = 'seller@jarvis.shop'),
       NOW(),
       DATE_SUB(NOW(), INTERVAL 2 DAY)
FROM member m
WHERE m.email = 'user@jarvis.shop'
  AND NOT EXISTS (SELECT 1 FROM inquiry i WHERE i.member_id = m.id AND i.title = '배송 지연 문의');

INSERT INTO inquiry (member_id, title, content, status, created_at)
SELECT m.id,
       '반품 절차 문의',
       '수령한 상품의 색상이 화면과 달라 반품하고 싶습니다. 절차를 알려주세요.',
       'PENDING',
       DATE_SUB(NOW(), INTERVAL 1 DAY)
FROM member m
WHERE m.email = 'user@jarvis.shop'
  AND NOT EXISTS (SELECT 1 FROM inquiry i WHERE i.member_id = m.id AND i.title = '반품 절차 문의');
