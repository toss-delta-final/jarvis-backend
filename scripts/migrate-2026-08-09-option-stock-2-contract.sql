-- 옵션별 재고 2단계 / 축소 (02 D33 개정 · AI팀 #454 합의 2026-08-09)
--
-- ⛔ 코드 배포(PR #139·#140·#142)가 끝나고 정상 동작을 확인한 뒤에 돌린다.
--    이 스크립트는 product.stock_quantity를 삭제한다 — 그 컬럼을 읽는 코드가 하나라도 살아 있으면
--    상품 조회 전체가 죽는다. 1단계와 나눠 둔 이유가 이것이고, 롤백 여지를 남기려고
--    컬럼을 마지막까지 들고 있는 것이다.
--
-- ⚠ 재동기화 문장을 뺐다 (2026-08-09 코드 배포 후 수정).
--    처음엔 맨 앞에 이런 문장이 있었다:
--
--      UPDATE product_stock ps JOIN product p ON p.id = ps.product_id
--      SET ps.quantity = p.stock_quantity WHERE ps.option_id IS NULL;
--
--    1단계와 코드 배포 사이에는 구 코드가 product.stock_quantity를 계속 깎으므로 그 값이 최신이고
--    product_stock이 낡는다 — 그래서 덮어쓰는 게 맞았다. **코드가 배포된 뒤로는 방향이 반대다.**
--    지금은 product_stock이 최신이고 컬럼이 멈춰 있어서, 덮어쓰면 배포 후 팔린 수량이 되살아난다.
--    창이 이미 지났으므로 덮어쓰지 않는다.

-- 사전 확인 ① 재고 행이 없는 상품이 있으면 안 된다 (0이어야 한다)
--   SELECT COUNT(*) FROM product p
--   WHERE NOT EXISTS (SELECT 1 FROM product_stock ps WHERE ps.product_id = p.id);
--
-- 사전 확인 ② 컬럼을 읽는 코드가 없는지 — 배포된 커밋이 #142 이상인지 본다
--   (#139에서 엔티티 매핑이 사라졌고, 그 앞 버전으로 롤백하면 이 컬럼이 다시 필요해진다)

ALTER TABLE product
    DROP CONSTRAINT chk_product_stock,
    DROP COLUMN stock_quantity;
