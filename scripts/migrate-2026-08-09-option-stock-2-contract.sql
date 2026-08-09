-- 옵션별 재고 2단계 / 축소 (02 D33 개정 · AI팀 #454 합의 2026-08-09)
--
-- ⛔ PR B(코드 전환)가 배포되어 정상 동작하는 것을 확인한 뒤에 돌린다.
--    이 스크립트는 product.stock_quantity를 삭제한다 — 그 컬럼을 읽는 코드가 하나라도 살아 있으면
--    상품 조회 전체가 죽는다. 1단계와 나눠 둔 이유가 이것이고, PR B 롤백 여지를 남기려고
--    컬럼을 마지막까지 들고 있는 것이다.
--
-- 사전 확인 — 이 값이 0이어야 한다(재고 행이 없는 상품이 있으면 안 된다):
--   SELECT COUNT(*) FROM product p
--   WHERE NOT EXISTS (SELECT 1 FROM product_stock ps WHERE ps.product_id = p.id);

-- 1. 옵션 없는 상품의 재고를 다시 맞춘다.
--    1단계 이후 PR B 배포 전까지는 구 코드가 product.stock_quantity를 계속 깎았으므로
--    (주문 결제·판매자 재고 수정) product_stock 쪽이 낡아 있다. 여기서 한 번 덮어쓴다.
--    옵션 있는 상품은 대상이 아니다 — 그쪽은 1단계에서 새로 정한 값이 정본이다.
UPDATE product_stock ps
JOIN product p ON p.id = ps.product_id
SET ps.quantity = p.stock_quantity,
    ps.updated_at = NOW()
WHERE ps.option_id IS NULL;

-- 2. 상품 재고 컬럼 제거. CHECK 제약을 먼저 떼야 컬럼이 지워진다
ALTER TABLE product
    DROP CONSTRAINT chk_product_stock,
    DROP COLUMN stock_quantity;
