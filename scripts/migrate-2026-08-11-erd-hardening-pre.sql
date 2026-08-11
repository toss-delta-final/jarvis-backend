-- ============================================================
-- 증분 마이그레이션 (2026-08-11, 1/2) — ERD 정비 중 **앱 배포 전** 몫 (02 D45·D46)
--
--   D45 표기 통일   : 로그 3종 ENUM→VARCHAR, recommendation_list_item에 created_at(NULL로 신설)
--   D46 인덱스 정비 : 소비 쿼리가 없는 8개 제거, 실쿼리가 쓰는 2개 추가
--
-- ⚠️ 이 파일이 **먼저**다. `ddl-auto: validate`라 새 앱은 기동 시 recommendation_list_item.created_at을
--   찾는다 — 컬럼이 없으면 기동 자체가 실패한다(2026-08-07 건과 같은 이유).
--   단 여기서는 **NULL 허용으로만** 만든다. 구 앱은 이 컬럼을 채우지 않으므로 지금 NOT NULL로 조이면
--   배포가 끝나기 전까지 추천 목록 저장이 실패한다. 조이는 건 -post가 앱 배포 뒤에 한다.
--   (Hibernate의 validate는 컬럼 존재·타입만 보고 nullable은 보지 않는다 — 2026-07-30/31 쌍과 같은 수법.)
--
-- 나머지(ENUM→VARCHAR·인덱스 증감)는 구 앱과도 새 앱과도 호환된다:
--   VARCHAR는 구 앱이 쓰던 문자열을 그대로 받고, 인덱스는 validate가 보지 않는다.
--
-- 재실행 무해: 전부 IF EXISTS / IF NOT EXISTS / MODIFY(멱등) / WHERE ... IS NULL.
--
-- 실행:
--   mariadb -h <host> -u <user> -p<pw> <db> < scripts/migrate-2026-08-11-erd-hardening-pre.sql
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1) 로그 3종 ENUM → VARCHAR (D45)
--    어휘는 Java enum이 계속 강제한다. 커머스 DB에 쓰는 주체가 Spring뿐이라(03 D7)
--    DB의 ENUM은 같은 규칙을 두 번째로 적을 뿐이고, 어휘가 늘 때 ALTER를 요구한다.
--    ⚠️ 테이블 재구축을 유발한다 — 로그 3종은 behavior_events보다 훨씬 작아 감수한다.
-- ------------------------------------------------------------

ALTER TABLE order_status_logs   MODIFY COLUMN actor_type  VARCHAR(20) NOT NULL;
ALTER TABLE product_change_logs MODIFY COLUMN change_type VARCHAR(20) NOT NULL;
ALTER TABLE account_event_logs  MODIFY COLUMN event_type  VARCHAR(20) NOT NULL;

-- ------------------------------------------------------------
-- 2) recommendation_list_item.created_at 신설 + 백필 (D45)
--    값이 늘 부모 목록과 같으므로(같은 트랜잭션에서 태어난다) 근사가 아니라 정확한 복원이다.
--    NOT NULL 전환은 -post.
-- ------------------------------------------------------------

ALTER TABLE recommendation_list_item
    ADD COLUMN IF NOT EXISTS created_at DATETIME(6) NULL AFTER product_id;

UPDATE recommendation_list_item i
  JOIN recommendation_list l ON l.list_id = i.list_id
   SET i.created_at = l.created_at
 WHERE i.created_at IS NULL;

-- 부모가 없는 고아 항목이 있으면 위 백필이 닿지 않아 -post의 NOT NULL이 실패한다.
-- 0이 아니면 -post 전에 사람이 판단한다(FK가 있으므로 정상 경로로는 생기지 않는다).
SELECT COUNT(*) AS orphan_reco_items_without_created_at
  FROM recommendation_list_item
 WHERE created_at IS NULL;

-- ------------------------------------------------------------
-- 3) 인덱스 제거 8개 (D46) — 리포지토리 전수 대조에서 소비 쿼리가 없었다.
--    "그 컬럼으로 찾기 시작하는가"가 기준이다 — GROUP BY·COUNT(DISTINCT)·정렬 재료로
--    값을 읽는 것은 인덱스 접근 경로가 아니다.
-- ------------------------------------------------------------

-- 쓰기 최다 테이블. session_key는 COUNT(DISTINCT)·NOT LIKE·창함수 PARTITION으로만,
-- occurred_at은 정렬 재료로만 쓰인다 — 이벤트 1건마다 갱신 비용만 내고 있었다
ALTER TABLE behavior_events
    DROP INDEX IF EXISTS idx_behavior_sess,
    DROP INDEX IF EXISTS idx_behavior_occur;

-- 조회가 전부 uk_reco_list(list_id) 경유다. 소유자 검증도 목록을 가져온 뒤 서비스가 비교한다
ALTER TABLE recommendation_list
    DROP INDEX IF EXISTS idx_reco_request,
    DROP INDEX IF EXISTS idx_reco_owner,
    DROP INDEX IF EXISTS idx_reco_created;

-- I-8은 IP로 거르지 않고 GROUP BY로 묶기만 한다
ALTER TABLE account_event_logs
    DROP INDEX IF EXISTS idx_aclog_ip;

-- AI 귀속 v1이 쓰던 인덱스들. v2(2026-08-10)가 판정 근거를 추천 명단으로 옮기면서
-- 이 컬럼들을 읽는 쿼리가 사라졌다. 컬럼은 C-2·I-2·O-1 계약이라 남긴다
ALTER TABLE cart_item  DROP INDEX IF EXISTS idx_cart_list;
ALTER TABLE order_item DROP INDEX IF EXISTS idx_order_item_list;

-- ------------------------------------------------------------
-- 4) 인덱스 추가 2개 (D46) — 실쿼리가 쓰는데 없었다
-- ------------------------------------------------------------

-- 자동 승인 스케줄러가 주기적으로 도는 (status, created_at) 스캔
ALTER TABLE claim
    ADD KEY IF NOT EXISTS idx_claim_status (status, created_at);

-- 평균 배송시간 자기조인(2026-08-06 짝짓기 키가 order_id→order_item_id로 개정)과
-- I-14 계열 6개 쿼리의 자사 스코프 조건
ALTER TABLE order_status_logs
    ADD KEY IF NOT EXISTS idx_oslog_item (order_item_id);

-- ------------------------------------------------------------
-- 5) 적용 확인
-- ------------------------------------------------------------

-- 5-1) 로그 3종이 varchar인가 (enum이 남아 있으면 실패)
SELECT table_name, column_name, data_type
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND (table_name, column_name) IN (('order_status_logs', 'actor_type'),
                                     ('product_change_logs', 'change_type'),
                                     ('account_event_logs', 'event_type'))
 ORDER BY table_name;

-- 5-2) 제거 8개는 0, 추가 2개는 2여야 한다
SELECT 'removed(expect 0)' AS kind, COUNT(DISTINCT index_name) AS n
  FROM information_schema.statistics
 WHERE table_schema = DATABASE()
   AND index_name IN ('idx_behavior_sess', 'idx_behavior_occur', 'idx_cart_list',
                      'idx_order_item_list', 'idx_reco_request', 'idx_reco_owner',
                      'idx_reco_created', 'idx_aclog_ip')
UNION ALL
SELECT 'added(expect 2)', COUNT(DISTINCT index_name)
  FROM information_schema.statistics
 WHERE table_schema = DATABASE()
   AND index_name IN ('idx_claim_status', 'idx_oslog_item');

-- 5-3) created_at이 생겼고(YES = 아직 NULL 허용, -post에서 조인다) 백필이 끝났는가
SELECT is_nullable,
       (SELECT COUNT(*) FROM recommendation_list_item WHERE created_at IS NULL) AS remaining_nulls
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND table_name = 'recommendation_list_item' AND column_name = 'created_at';
