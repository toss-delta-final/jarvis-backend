-- Phase 1 시드 — SELLER 계정 (06 Phase 1 완료 조건: SELLER로 USER 전용 API 403 확인용)
-- SELLER/ADMIN은 가입 API로 생성 불가(02 member) — 시드로만 만든다.
-- 로그인: seller@jarvis.shop / seller1234  (BCrypt 해시는 로컬 개발용 — 배포 시 교체)
-- 적용: docker exec -i jarvis-mariadb mariadb -ujarvis -pjarvis-local jarvis < scripts/seed-phase1.sql

INSERT INTO member (email, password, nickname, role, gender, birth_date,
                    agreed_terms_at, agreed_privacy_at, created_at)
SELECT 'seller@jarvis.shop',
       '$2a$10$ofCmr3m/dvJuwlJD7625ZeSPWxy7tzXr9Dh9rLzEVXMlMj2MB9xCi',
       'jarvis-seller', 'SELLER', 'MALE', '1990-01-01',
       NOW(), NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM member WHERE email = 'seller@jarvis.shop');
