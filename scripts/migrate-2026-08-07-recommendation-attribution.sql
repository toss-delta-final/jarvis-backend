-- 담기·주문에 추천 귀속 저장 (C-2 · I-2 · O-1, 2026-08-07)
--
-- schema.sql은 최초 생성 전용(재실행 불가)이라, 이미 스키마가 깔린 DB(배포 DB·기존 로컬)에는 이 파일을 적용한다.
-- schema.sql에도 같은 정의가 반영되어 있어 신규 DB와 최종 상태가 같다.
--
-- 왜: 노션 C-2·I-2·O-1이 recommendationContext(recommendationRequestId·listId)를 명세하는데
-- 저장할 칸이 없어 서버가 조용히 버리고 있었다. 그 결과 챗봇·추천이 만든 매출이 집계에서 0으로 잡힌다.
-- 귀속 4컬럼은 recommendation_list·behavior_events엔 이미 있고, 전환 지점 두 곳만 비어 있었다.
--
-- 기존 행 백필은 없다 — 지금까지의 담기·주문은 출처를 받아둔 적이 없어 복원할 원본이 존재하지 않는다.
-- NULL이 "추천 경유 아님"과 "출처 미상"을 겸하지만, 이 마이그레이션 이전 데이터에 한해서다.
--
-- FK는 걸지 않는다 — behavior_events가 같은 4컬럼을 FK 없이 두고 인덱스만 가진 것과 같은 이유다(02 D38).
-- FK를 걸면 만료된 추천 목록을 정리할 때 장바구니·주문 이력이 삭제를 막는다.

ALTER TABLE cart_item
    ADD COLUMN recommendation_request_id CHAR(36)    NULL AFTER quantity,
    ADD COLUMN list_id                   VARCHAR(64) NULL AFTER recommendation_request_id,
    ADD KEY idx_cart_list (list_id);

ALTER TABLE order_item
    ADD COLUMN recommendation_request_id CHAR(36)    NULL AFTER status_changed_at,
    ADD COLUMN list_id                   VARCHAR(64) NULL AFTER recommendation_request_id,
    ADD KEY idx_order_item_list (list_id);
