-- ============================================================
-- 증분 마이그레이션 (2026-08-11, 2/2) — ERD 정비 중 **앱 배포 후** 몫 (02 D44·D45)
--
--   D44 제약 강화 : UNIQUE의 NULL 구멍을 가상 컬럼으로 막고, 옵션↔상품 소속을 복합 FK로 내린다
--   D45 마무리    : recommendation_list_item.created_at을 NOT NULL로 조인다
--
-- ⚠️ -pre 적용 → **새 이미지 배포 → 헬스체크 UP 확인** → 이 파일. 두 가지 이유가 겹친다:
--   ① uk_cart_*가 option_key로 바뀌면 동시 담기 경합이 종전의 "중복 행 생성 후 자가치유"가 아니라
--      제약 위반 예외로 드러난다 — 그걸 잡아 재시도하는 코드가 먼저 떠 있어야 한다.
--   ② created_at NOT NULL은 앱이 그 값을 채우기 시작한 뒤에야 조일 수 있다(2026-07-31 건과 같은 이유).
--   구 앱에 이 스키마를 물리면 드문 경합에서 그 요청만 500이 되고, 추천 목록 저장이 실패한다.
--
-- ⚠️ §0을 먼저 돌린다. 앞의 **두 SELECT(①②)가 0이 아니면 여기서 멈추고 사람이 판단한다** —
--   그대로 진행하면 §2의 ALTER가 실패하며 중간 상태로 남는다. 재고 행 중복과 옵션 소속 위반은
--   자동 병합하지 않는다: 어느 수량이 맞는지, 어느 옵션이 맞는지 DB가 알 수 없고 둘 다 돈에 닿는다.
--   **③(created_at 잔여)은 0이 아니어도 정상이다** — 구 앱이 -pre 이후 배포 전까지 넣은 행이고,
--   §3의 백필이 채운다. 판단할 것이 없다.
--
-- 재실행: §1 병합은 HAVING COUNT(*) > 1이라 두 번째부터 0행. DDL은 IF EXISTS/IF NOT EXISTS로
--   감쌌으나 ADD CONSTRAINT엔 그 문법이 없어 재실행 시 "Duplicate key name"으로 멈춘다 —
--   §4 검증으로 어디까지 갔는지 확인하고 그 지점부터 이어간다.
--
-- 실행:
--   mariadb -h <host> -u <user> -p<pw> <db> < scripts/migrate-2026-08-11-erd-hardening-post.sql
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 0) 사전 점검 — 세 값이 모두 0이어야 진행한다
-- ------------------------------------------------------------

-- ① 무옵션 재고 행 중복 (한 상품에 option_id NULL 행이 2개 이상)
SELECT COUNT(*) AS stock_null_dupes
  FROM (SELECT product_id FROM product_stock
         WHERE option_id IS NULL
         GROUP BY product_id HAVING COUNT(*) > 1) d;

-- ② 옵션 소속 위반 — 자식이 든 option_id가 그 자식의 product_id 소속이 아닌 행
SELECT
    (SELECT COUNT(*) FROM product_stock s JOIN product_option o ON o.id = s.option_id
      WHERE o.product_id <> s.product_id) AS stock_mismatch,
    (SELECT COUNT(*) FROM cart_item c JOIN product_option o ON o.id = c.option_id
      WHERE o.product_id <> c.product_id) AS cart_mismatch,
    (SELECT COUNT(*) FROM order_item i JOIN product_option o ON o.id = i.option_id
      WHERE o.product_id <> i.product_id) AS order_item_mismatch;

-- ③ created_at 백필 잔여 — ⚠️ 이 값만은 0이 아니어도 정상이다(위 두 개와 성격이 다르다).
--    -pre의 백필 이후 새 앱이 뜨기 전까지 구 앱이 넣은 행은 이 컬럼이 비어 있다.
--    그래서 §3이 백필을 한 번 더 돌린다 — 사람이 판단할 것이 없다. FK가 부모를 보장하고
--    부모의 created_at은 NOT NULL이라, 남는 행 없이 전부 채워진다.
--    (2026-08-11 dev 적용에서 24행이 이 경우였다 — 창이 CD 소요시간만큼 열려 있었다.)
SELECT COUNT(*) AS reco_items_without_created_at_before_backfill
  FROM recommendation_list_item WHERE created_at IS NULL;

-- ------------------------------------------------------------
-- 1) 데이터 정리 — cart_item 중복 라인 병합 (D44 선행 조건)
--    규칙은 서비스의 자가치유(CartService.consolidate)와 동일: 가장 오래된 id에 수량을 합치고
--    상한 99로 클램프(CartItem.MAX_QUANTITY·addQuantity와 같은 규칙), 나머지 행 삭제.
--    UPDATE가 DELETE보다 먼저다 — 합계에 삭제될 행이 포함돼야 한다.
-- ------------------------------------------------------------

-- 회원 라인
UPDATE cart_item c
  JOIN (SELECT MIN(id) AS keep_id, LEAST(SUM(quantity), 99) AS total
          FROM cart_item
         WHERE member_id IS NOT NULL
         GROUP BY member_id, product_id, IFNULL(option_id, 0)
        HAVING COUNT(*) > 1) d ON d.keep_id = c.id
   SET c.quantity = d.total;

DELETE c FROM cart_item c
  JOIN (SELECT MIN(id) AS keep_id, member_id, product_id, IFNULL(option_id, 0) AS okey
          FROM cart_item
         WHERE member_id IS NOT NULL
         GROUP BY member_id, product_id, IFNULL(option_id, 0)
        HAVING COUNT(*) > 1) d
    ON c.member_id = d.member_id AND c.product_id = d.product_id
   AND IFNULL(c.option_id, 0) = d.okey AND c.id <> d.keep_id;

-- 게스트 라인
UPDATE cart_item c
  JOIN (SELECT MIN(id) AS keep_id, LEAST(SUM(quantity), 99) AS total
          FROM cart_item
         WHERE guest_id IS NOT NULL
         GROUP BY guest_id, product_id, IFNULL(option_id, 0)
        HAVING COUNT(*) > 1) d ON d.keep_id = c.id
   SET c.quantity = d.total;

DELETE c FROM cart_item c
  JOIN (SELECT MIN(id) AS keep_id, guest_id, product_id, IFNULL(option_id, 0) AS okey
          FROM cart_item
         WHERE guest_id IS NOT NULL
         GROUP BY guest_id, product_id, IFNULL(option_id, 0)
        HAVING COUNT(*) > 1) d
    ON c.guest_id = d.guest_id AND c.product_id = d.product_id
   AND IFNULL(c.option_id, 0) = d.okey AND c.id <> d.keep_id;

-- ------------------------------------------------------------
-- 2) 제약 강화 (D44)
--    복합 FK가 참조할 UNIQUE를 먼저 만든 뒤, 자식마다
--    [옛 FK 해제 → 인덱스 교체 → 새 복합 FK]. 순서를 지켜야 InnoDB가
--    "FK가 쓰는 인덱스를 지운다"고 거부하지 않는다.
--    가상 컬럼은 실 컬럼과 FK를 건드리지 않고 인덱스에만 센티널(NULL→0)을 넣는다.
-- ------------------------------------------------------------

ALTER TABLE product_option
    ADD UNIQUE KEY IF NOT EXISTS uk_product_option_id_product (id, product_id);

-- 2-1) product_stock
ALTER TABLE product_stock
    ADD COLUMN IF NOT EXISTS option_key BIGINT AS (IFNULL(option_id, 0)) VIRTUAL AFTER option_id;

ALTER TABLE product_stock
    DROP FOREIGN KEY IF EXISTS fk_product_stock_option;

ALTER TABLE product_stock
    DROP INDEX IF EXISTS uk_product_stock,
    DROP INDEX IF EXISTS idx_product_stock_option,
    ADD UNIQUE KEY uk_product_stock (product_id, option_key),
    ADD KEY idx_product_stock_option (option_id, product_id);

ALTER TABLE product_stock
    ADD CONSTRAINT fk_product_stock_option FOREIGN KEY (option_id, product_id)
        REFERENCES product_option (id, product_id) ON DELETE RESTRICT;

-- 2-2) cart_item
ALTER TABLE cart_item
    ADD COLUMN IF NOT EXISTS option_key BIGINT AS (IFNULL(option_id, 0)) VIRTUAL AFTER option_id;

ALTER TABLE cart_item
    DROP FOREIGN KEY IF EXISTS fk_cart_option;

ALTER TABLE cart_item
    DROP INDEX IF EXISTS uk_cart_member,
    DROP INDEX IF EXISTS uk_cart_guest,
    DROP INDEX IF EXISTS fk_cart_option,          -- 구 단일 FK를 받치던 자동 생성 인덱스
    ADD UNIQUE KEY uk_cart_member (member_id, product_id, option_key),
    ADD UNIQUE KEY uk_cart_guest (guest_id, product_id, option_key),
    ADD KEY idx_cart_option (option_id, product_id);

ALTER TABLE cart_item
    ADD CONSTRAINT fk_cart_option FOREIGN KEY (option_id, product_id)
        REFERENCES product_option (id, product_id) ON DELETE RESTRICT;

-- 2-3) order_item
ALTER TABLE order_item
    DROP FOREIGN KEY IF EXISTS fk_order_item_option;

ALTER TABLE order_item
    DROP INDEX IF EXISTS fk_order_item_option,    -- 구 단일 FK를 받치던 자동 생성 인덱스
    ADD KEY idx_order_item_option (option_id, product_id);

ALTER TABLE order_item
    ADD CONSTRAINT fk_order_item_option FOREIGN KEY (option_id, product_id)
        REFERENCES product_option (id, product_id) ON DELETE RESTRICT;

-- ------------------------------------------------------------
-- 3) created_at 백필 재실행 → NOT NULL 마무리 (D45)
--
--    ⚠️ 백필이 -pre와 여기 두 번 있는 건 중복이 아니다. -pre가 채운 뒤 새 앱이 뜨기까지
--    구 앱이 계속 목록을 저장하는데, 그 행들은 이 컬럼을 채우지 않는다(그러라고 -pre에서
--    NULL 허용으로 뒀다). 창의 길이는 CD 소요시간이고, dev에서는 24행이 그 사이 들어왔다.
--    여기서 한 번 더 돌려야 그 창의 행들이 채워진다.
--
--    ⚠️ 이 스크립트는 **앱 배포가 끝난 뒤**에 돌려야 한다(파일 머리말). 구 앱이 아직 돌고 있으면
--    백필과 NOT NULL 사이에 또 NULL이 들어와 ALTER가 실패하고, 성공하더라도 그 뒤 구 앱의
--    목록 저장이 전부 실패한다.
-- ------------------------------------------------------------

UPDATE recommendation_list_item i
  JOIN recommendation_list l ON l.list_id = i.list_id
   SET i.created_at = l.created_at
 WHERE i.created_at IS NULL;

-- 남은 NULL이 있으면 여기서 실패한다(조용히 넘어가지 않는 게 맞다).
-- 실패한다면 부모 없는 행이 있다는 뜻이므로 fk_reco_item_list부터 확인한다.
ALTER TABLE recommendation_list_item
    MODIFY COLUMN created_at DATETIME(6) NOT NULL;

-- ------------------------------------------------------------
-- 4) 적용 확인
-- ------------------------------------------------------------

-- 4-1) 가상 컬럼 2개가 VIRTUAL GENERATED로 붙었는가
SELECT table_name, column_name, extra
  FROM information_schema.columns
 WHERE table_schema = DATABASE() AND column_name = 'option_key'
 ORDER BY table_name;

-- 4-2) 복합 FK 3개가 2컬럼인가 — 각 행의 cols가 2여야 한다
SELECT constraint_name, table_name, COUNT(*) AS cols
  FROM information_schema.key_column_usage
 WHERE table_schema = DATABASE()
   AND constraint_name IN ('fk_product_stock_option', 'fk_cart_option', 'fk_order_item_option')
 GROUP BY constraint_name, table_name
 ORDER BY table_name;

-- 4-3) UNIQUE가 option_key를 축으로 잡았는가 (option_id가 나오면 실패)
SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols
  FROM information_schema.statistics
 WHERE table_schema = DATABASE()
   AND index_name IN ('uk_product_stock', 'uk_cart_member', 'uk_cart_guest')
 GROUP BY index_name, table_name
 ORDER BY index_name;

-- 4-4) created_at이 NOT NULL인가
SELECT column_name, is_nullable
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND table_name = 'recommendation_list_item' AND column_name = 'created_at';
