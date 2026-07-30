-- ============================================================
-- 증분 마이그레이션 (2026-07-30) — 추천 목록 영구 사본 + behavior_events 확장 (02 D38)
--
-- 대상: schema.sql이 2026-07-30 이전에 적용된 "이미 운영 중인 DB" (배포 DB · 기존 로컬 DB).
--   신규 DB는 이 파일이 필요 없다 — schema.sql 전체 적용에 이미 포함되어 있다.
-- 재실행 무해(IF NOT EXISTS) — 몇 번을 다시 돌려도 같은 상태로 수렴한다.
-- 기존 데이터 보존: behavior_events에 쌓인 행은 그대로 두고 NULL 컬럼만 덧붙인다(잠금 최소, 순간 적용).
--
-- ⚠️ 적용 순서: 이 파일을 먼저 적용한 뒤 새 앱을 기동한다.
--   앱은 ddl-auto=validate라 recommendation_list 테이블이 없으면 기동 자체가 실패한다.
--
-- 실행:
--   mariadb -h <host> -u <user> -p<pw> <db> < scripts/migrate-2026-07-30-recommendation-list.sql
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1) 신설 — 추천 목록 영구 사본 (schema.sql의 정의와 동일)
--    I-21(채팅)·I-22(홈) 공용. Redis는 CH-5 조회 전용 10분 캐시고, 이 두 테이블이 정본.
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS recommendation_list (
    id                        BIGINT      NOT NULL AUTO_INCREMENT,
    list_id                   VARCHAR(64) NOT NULL,        -- >=128bit 무작위. CH-5 조회 키이자 이벤트 귀속 키
    recommendation_request_id CHAR(36)    NOT NULL,        -- 추천 실행 1회. 한 실행에 목록이 여러 개 달릴 수 있다
    surface                   VARCHAR(10) NOT NULL,        -- CHAT(I-21) / HOME(I-22)
    list_type                 VARCHAR(10) NOT NULL,        -- PICK_ONE(택1) / BUY_ALL(세트)
    source                    VARCHAR(20) NOT NULL,        -- AI_RECOMMENDED / POPULAR_FALLBACK
    session_id                CHAR(36)    NULL,            -- 채팅만. 홈은 세션이 없어 NULL
    member_id                 BIGINT      NULL,            -- 신원 스냅샷 — CH-5 소유자 검증 + 이벤트 귀속
    guest_id                  CHAR(36)    NULL,            -- 〃. FK 미설정
    label                     VARCHAR(50) NULL,            -- BUY_ALL이면 세트명("알뜰"), PICK_ONE이면 니즈명("파우치")
    total_budget              INT         NULL,            -- BUY_ALL + 예산 발화 시에만
    item_count                INT         NOT NULL,        -- recommendation_generated의 properties.itemCount 원천
    created_at                DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reco_list (list_id),
    KEY idx_reco_request (recommendation_request_id),
    KEY idx_reco_owner   (member_id, created_at),
    KEY idx_reco_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS recommendation_list_item (
    list_id     VARCHAR(64) NOT NULL,
    position    INT         NOT NULL,                      -- 0-based. 리랭킹 순서 = 렌더 순서 = 이벤트의 순위
    product_id  BIGINT      NOT NULL,                      -- FK 미설정: 상품이 지워져도 "그때 뭘 추천했는지"는 남아야 한다
    PRIMARY KEY (list_id, position),
    KEY idx_reco_item_product (product_id),
    CONSTRAINT fk_reco_item_list FOREIGN KEY (list_id)
        REFERENCES recommendation_list (list_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------
-- 2) behavior_events 확장 — 발생 시각 + 추천 귀속 4종 (전부 NULL 허용)
--    기존 행은 새 컬럼이 NULL로 남는 게 정상이다. 백필·NOT NULL 전환은
--    엔티티가 이 컬럼을 실제로 쓰기 시작하는 E-1 확장 작업에서 한다(schema.sql 주석·02 D38).
-- ------------------------------------------------------------

ALTER TABLE behavior_events
    ADD COLUMN IF NOT EXISTS occurred_at               DATETIME(6) NULL AFTER properties,
    ADD COLUMN IF NOT EXISTS recommendation_request_id CHAR(36)    NULL AFTER occurred_at,
    ADD COLUMN IF NOT EXISTS list_id                   VARCHAR(64) NULL AFTER recommendation_request_id,
    ADD COLUMN IF NOT EXISTS surface                   VARCHAR(10) NULL AFTER list_id,
    ADD COLUMN IF NOT EXISTS position                  INT         NULL AFTER surface,
    ADD KEY IF NOT EXISTS idx_behavior_list  (list_id, event_type),
    ADD KEY IF NOT EXISTS idx_behavior_occur (occurred_at);

-- ------------------------------------------------------------
-- 3) 적용 확인 — 아래 결과가 new_tables=2, behavior_cols=14 이면 성공
-- ------------------------------------------------------------

SELECT
    (SELECT COUNT(*) FROM information_schema.tables
      WHERE table_schema = DATABASE()
        AND table_name IN ('recommendation_list', 'recommendation_list_item')) AS new_tables,
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'behavior_events')      AS behavior_cols;
