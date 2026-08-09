-- 옵션별 재고 1b단계 / 인덱스 (02 D33 개정)
--
-- ✅ 1단계와 마찬가지로 지금 돌려도 안전하다 — 인덱스만 더한다.
--    다만 **PR B 배포 전에는 돌아 있어야 한다** — 아래 EXISTS가 상품 검색(I-1)·인기(P-4)의
--    모든 후보에 걸리는데, 인덱스가 없으면 상품마다 재고 행을 테이블에서 되짚는다.
--    review 커버링 인덱스(2026-08-09 I-1)에서 겪은 것과 같은 구조다.
--
--   SELECT ... FROM product p
--   WHERE EXISTS (SELECT 1 FROM product_stock ps WHERE ps.product_id = p.id AND ps.quantity > 0)
--
-- uk_product_stock(product_id, option_id)는 product_id로 찾는 데까지만 쓰이고 quantity가 없어서
-- 행마다 되짚기가 남는다. quantity를 인덱스에 넣으면 그 되짚기가 사라진다.

ALTER TABLE product_stock
    ADD KEY idx_product_stock_purchasable (product_id, quantity);

-- 확인 — Extra에 "Using index"가 떠야 한다
-- EXPLAIN SELECT 1 FROM product_stock ps WHERE ps.product_id = 1 AND ps.quantity > 0;
