-- 옵션별 재고 1단계 / 확장 (02 D33 개정 · AI팀 #454 합의 2026-08-09)
--
-- ✅ 지금 돌려도 안전하다 — 기존 컬럼을 하나도 건드리지 않고 더하기만 한다.
--    현재 배포된 앱은 여전히 product.stock_quantity를 읽고 쓰며, 이 스크립트는 거기에 영향이 없다.
--
-- ⚠ 2단계(컬럼 삭제)는 여기서 돌리지 않는다 — migrate-2026-08-09-option-stock-2-contract.sql 참조.
--    순서: [1단계] → [PR B 배포] → [2단계]. 1단계를 건너뛰고 PR B를 배포하면 테이블이 없어 앱이 죽는다.
--
-- ⚠ 중간에 실패해서 다시 돌릴 때: mariadb CLI는 첫 에러에서 멈추므로 앞부분만 적용돼 있다.
--    처음부터 다시 돌리려면 그때까지 만들어진 것을 먼저 되돌린다 —
--      DROP TABLE IF EXISTS product_stock;
--      ALTER TABLE order_item DROP FOREIGN KEY fk_order_item_option, DROP COLUMN option_id;   -- 4번까지 갔다면
--      ALTER TABLE product_change_logs DROP COLUMN option_id;                                  -- 5번까지 갔다면
--    이 스크립트에 DROP을 넣어두지 않은 것은 의도다 — 성공 후 실수로 재실행하면 재고가 날아간다.

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
--    ⚠ id에 곱셈을 쓰지 말 것 — 이 프로젝트의 id는 JS 안전 정수를 넘을 만큼 커서(응답에서 문자열로
--      내리는 이유가 그것이다) `po.id * 37`이 BIGINT 범위를 넘겼다(2026-08-09 실행 시 ERROR 1690).
--      CRC32는 32비트로 접어 돌려주므로 id가 아무리 커도 넘치지 않고, 값도 잘 흩어진다.
INSERT INTO product_stock (product_id, option_id, quantity, created_at)
SELECT po.product_id,
       po.id,
       CASE WHEN po.id % 7 = 0 THEN 0 ELSE 20 + CRC32(po.id) % 81 END,
       NOW()
FROM product_option po;

-- 3. 옵션 없는 상품 — 기존 재고를 그대로 옮긴다(여긴 실제 값이 있으므로 만들어내지 않는다).
--    이 값은 PR B 배포 전까지 낡을 수 있다 — 2단계에서 다시 맞춘다.
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

-- 5. 변경 로그에 옵션 참조 — "재고 100 → 50"이 어느 옵션 얘긴지 판매자가 알아야 한다.
--    PRICE·STATUS는 상품 단위라 NULL로 남는다. 기존 STOCK 로그도 NULL — 그 시절엔 상품 단위였다.
ALTER TABLE product_change_logs
    ADD COLUMN option_id BIGINT NULL AFTER product_id;

-- 확인용 — 상품 수와 재고 행 수가 맞는지
-- SELECT (SELECT COUNT(*) FROM product) AS products,
--        (SELECT COUNT(*) FROM product_option) AS options,
--        (SELECT COUNT(*) FROM product_stock) AS stock_rows,
--        (SELECT COUNT(*) FROM product_stock WHERE quantity = 0) AS sold_out_rows;
