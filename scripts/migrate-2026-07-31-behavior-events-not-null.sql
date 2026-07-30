-- ============================================================
-- 증분 마이그레이션 (2026-07-31) — behavior_events의 occurred_at·client_event_id를 NOT NULL로 (02 D38·D40)
--
-- ⚠️ 적용 시점: **E-1 확장이 배포된 뒤**에 적용한다(migrate-2026-07-30-recommendation-list.sql과 반대).
--   앱이 occurred_at을 채우기 시작한 뒤여야 조일 수 있다. 구 버전 앱이 이 스키마에 INSERT하면
--   occurred_at이 비어 제약 위반으로 **그 이벤트만 유실**된다(앱은 계속 서비스한다 — 적재는 비동기).
--   가장 안전한 순서: 새 이미지 배포 → 헬스체크 UP 확인 → 이 파일 적용.
--
-- 왜 NOT NULL인가
--   client_event_id: 중복 차단 키인데 **MariaDB의 UNIQUE는 NULL끼리를 중복으로 보지 않는다** —
--     값이 비면 제약이 걸려 있어도 아무 일도 하지 않아 재전송 100번이 100행으로 쌓인다.
--   occurred_at: event-time 분석의 근거. 서버가 이상치·누락을 created_at으로 대체하므로
--     앞으로 들어오는 행은 항상 값이 있다(대체분은 properties._timeShifted로 구분).
--
-- 기존 데이터: 삭제하지 않고 백필한다(배포 DB엔 테스트로 쌓인 실데이터가 있다).
-- 재실행 무해: 백필은 WHERE ... IS NULL이라 두 번째부터 0행, MODIFY는 이미 NOT NULL이면 무변경.
--
-- 실행:
--   mariadb -h <host> -u <user> -p<pw> <db> < scripts/migrate-2026-07-31-behavior-events-not-null.sql
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1) 백필 — occurred_at은 수신 시각으로. 발생 시각을 모르는 과거 행이므로 created_at이 최선의 근사다.
-- ------------------------------------------------------------

UPDATE behavior_events
   SET occurred_at = created_at
 WHERE occurred_at IS NULL;

-- ------------------------------------------------------------
-- 2) 백필 — client_event_id는 행마다 새 UUID. UUID()는 행 단위로 평가되므로 UNIQUE와 충돌하지 않는다.
--    무작위인 이유: 과거 행에는 "무엇의 중복인지" 판별할 원본 키가 없다. 목적은 제약을 세우는 것이고,
--    이 행들은 이미 적재가 끝나 재전송될 일이 없다.
-- ------------------------------------------------------------

UPDATE behavior_events
   SET client_event_id = UUID()
 WHERE client_event_id IS NULL;

-- ------------------------------------------------------------
-- 3) 제약 적용 — 남은 NULL이 있으면 여기서 실패한다(조용히 넘어가지 않는 게 맞다).
-- ------------------------------------------------------------

ALTER TABLE behavior_events
    MODIFY COLUMN occurred_at     DATETIME(6) NOT NULL,
    MODIFY COLUMN client_event_id CHAR(36)    NOT NULL;

-- ------------------------------------------------------------
-- 4) 적용 확인 — 두 값이 모두 'NO'면 성공
-- ------------------------------------------------------------

SELECT COLUMN_NAME, IS_NULLABLE
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND table_name = 'behavior_events'
   AND COLUMN_NAME IN ('occurred_at', 'client_event_id')
 ORDER BY COLUMN_NAME;
