-- 옵션별 재고 (02 D33 개정 · 노션 I-1/I-2/I-3/I-9/I-10/I-11/I-18/P-2/P-4/C-1/C-2/C-3/O-1/S-1/S-3)
-- AI팀 #454 요청 → BE 제안 → 2026-08-09 합의. 재고를 product.stock_quantity 컬럼에서
-- product_stock 테이블로 옮긴다. 재고가 사는 곳을 한 군데로 유지하려고 상품 쪽 합계 컬럼은 남기지 않는다.
--
-- ⚠ 순서를 지킬 것 — 3단계(백필)가 끝나기 전에 5단계(컬럼 삭제)를 돌리면 옵션 없는 상품의 재고가 사라진다.

-- 1. 재고 테이블
CREATE TABLE product_stock (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    product_id  BIGINT   NOT NULL,
    option_id   BIGINT   NULL,
    quantity    INT      NOT NULL DEFAULT 0,
    created_at  DATETIME NOT NULL,
    updated_at  DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_stock (product_id, option_id),
    KEY idx_product_stock_option (option_id),
    CONSTRAINT chk_product_stock_quantity CHECK (quantity >= 0),
    CONSTRAINT fk_product_stock_product FOREIGN KEY (product_id)
        REFERENCES product (id) ON DELETE RESTRICT,
    CONSTRAINT fk_product_stock_option FOREIGN KEY (option_id)
        REFERENCES product_option (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. 옵션 있는 상품 — 옵션마다 한 행.
--    초기값을 상품 재고에서 나누지 않고 새로 정하는 이유: 원본 데이터에 옵션별 재고가 없어서
--    어떻게 나눠도 근거가 없고, 전부 같은 값이면 품절이 하나도 없어 이 기능이 도는지 확인할 수 없다.
--    id에서 결정적으로 유도한다 — RAND()를 쓰면 다시 돌릴 때 다른 화면이 나온다.
INSERT INTO product_stock (product_id, option_id, quantity, created_at)
SELECT po.product_id,
       po.id,
       CASE WHEN po.id % 7 = 0 THEN 0 ELSE 20 + (po.id * 37) % 81 END,
       NOW()
FROM product_option po;

-- 3. 옵션 없는 상품 — 기존 재고를 그대로 옮긴다(여긴 실제 값이 있으므로 만들어내지 않는다)
INSERT INTO product_stock (product_id, option_id, quantity, created_at)
SELECT p.id, NULL, p.stock_quantity, NOW()
FROM product p
WHERE NOT EXISTS (SELECT 1 FROM product_option po WHERE po.product_id = p.id);

-- 4. 주문 항목에 옵션 참조 — 취소·반품 재고 복원이 어느 행으로 되돌릴지 알아야 한다.
--    기존 행은 option_name 스냅샷으로 최선을 다해 채우되, 같은 이름의 옵션이 둘 이상이면
--    임의로 하나가 잡힌다. 복원 기능이 MVP 미구현이라 지금은 무해하고, 앞으로 쌓이는 행은 정확하다.
ALTER TABLE order_item
    ADD COLUMN option_id BIGINT NULL AFTER product_id,
    ADD CONSTRAINT fk_order_item_option FOREIGN KEY (option_id)
        REFERENCES product_option (id) ON DELETE RESTRICT;

UPDATE order_item oi
JOIN product_option po ON po.product_id = oi.product_id AND po.name = oi.option_name
SET oi.option_id = po.id
WHERE oi.option_name IS NOT NULL;

-- 5. 변경 로그에 옵션 참조 — "재고 100 → 50"이 어느 옵션 얘기인지 판매자가 알아야 한다.
--    PRICE·STATUS는 상품 단위라 NULL로 남는다. 기존 STOCK 로그도 NULL — 그 시절엔 상품 단위였다.
ALTER TABLE product_change_logs
    ADD COLUMN option_id BIGINT NULL AFTER product_id;

-- 6. 상품 재고 컬럼 제거. CHECK 제약을 먼저 떼야 컬럼이 지워진다
ALTER TABLE product
    DROP CONSTRAINT chk_product_stock,
    DROP COLUMN stock_quantity;
