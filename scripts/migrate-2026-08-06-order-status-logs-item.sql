-- 상태 전이 로그에 아이템 해상도 추가 (I-30 판매자 발송 · D32 개정, 2026-08-06)
--
-- schema.sql은 최초 생성 전용(재실행 불가)이라, 이미 스키마가 깔린 DB(배포 DB·기존 로컬)에는 이 파일을 적용한다.
-- schema.sql에도 같은 정의가 반영되어 있어 신규 DB와 최종 상태가 같다.
--
-- 왜: 발송(ORDERED→SHIPPING)이 판매자 행위로 바뀌면서 전이가 아이템 단위로 일어난다. 한 주문에 여러
-- 브랜드 아이템이 섞이므로 order_id만으로는 "무엇이 발송됐나"를 복원할 수 없다.
--
-- 기존 행 백필은 없다. 지금까지 쌓인 전이는 실제로 전부 주문 단위 배치였으므로 NULL이 사실과 맞다.
-- 앞으로 아이템 상태 전이(SHIPPING/DELIVERED/CANCELLED/RETURNED)는 항상 값이 채워지고,
-- 주문 상태 전이(PENDING/PAID/PAYMENT_FAILED/주문 CANCELLED)만 NULL로 남는다.
-- CONFIRMED는 원래 로그에 기록하지 않는다(01 §6.5 규칙 2).
--
-- FK는 걸지 않는다 — 이 테이블은 append-only 로그라 원래 FK를 하나도 두지 않는 설계다(02 D32).

ALTER TABLE order_status_logs ADD COLUMN order_item_id BIGINT NULL AFTER order_id;
