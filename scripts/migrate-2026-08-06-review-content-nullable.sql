-- 후기 본문을 선택 입력으로 (M-1 · 별점만 남기기 허용, 2026-08-06)
--
-- schema.sql은 최초 생성 전용(재실행 불가)이라, 이미 스키마가 깔린 DB(배포 DB·기존 로컬)에는 이 파일을 적용한다.
-- schema.sql에도 같은 정의가 반영되어 있어 신규 DB와 최종 상태가 같다.
--
-- 기존 행은 전부 본문이 있으므로 데이터 백필은 없다. NULL은 앞으로 쓰이는 "별점만" 후기에만 생긴다.

ALTER TABLE review MODIFY COLUMN content TEXT NULL;
