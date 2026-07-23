-- 시드 — 데모 계정 (판매자 + 구매자). 카탈로그/주문에 앞서 적용한다.
-- SELLER/ADMIN은 가입 API로 생성 불가(02 member) — 시드로만 만든다.
-- 로그인: 전 계정 비밀번호 seller1234 (BCrypt 해시는 로컬 개발용 — 배포 시 교체).
-- 적용: docker exec -i jarvis-mariadb mariadb -ujarvis -pjarvis-local jarvis < scripts/seed-accounts.sql
-- 재실행 무해(NOT EXISTS). user@jarvis.shop은 가입 API로 별도 생성(문의 데모 대상).

-- ── 판매자 1호 (06 Phase 1: SELLER로 USER 전용 API 403 확인용) ──
INSERT INTO member (email, password, nickname, role, gender, birth_date,
                    agreed_terms_at, agreed_privacy_at, created_at)
SELECT 'seller@jarvis.shop',
       '$2a$10$ofCmr3m/dvJuwlJD7625ZeSPWxy7tzXr9Dh9rLzEVXMlMj2MB9xCi',
       'jarvis-seller', 'SELLER', 'MALE', '1990-01-01',
       NOW(), NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM member WHERE email = 'seller@jarvis.shop');

-- ── 판매자 2호 (대시보드/분석 데모의 소유 판매자 — 브랜드 연결은 seed-commerce-demo.sql) ──
INSERT INTO member (email, password, nickname, role, gender, birth_date,
                    agreed_terms_at, agreed_privacy_at, created_at)
SELECT 'seller2@jarvis.shop',
       '$2a$10$ofCmr3m/dvJuwlJD7625ZeSPWxy7tzXr9Dh9rLzEVXMlMj2MB9xCi',
       'jarvis-seller2', 'SELLER', 'FEMALE', '1992-05-15',
       NOW(), NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM member WHERE email = 'seller2@jarvis.shop');

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
