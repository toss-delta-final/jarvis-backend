-- review 평점 집계를 커버링 인덱스로 (I-1 검색 · 2026-08-09)
--
-- schema.sql은 최초 생성 전용(재실행 불가)이라, 이미 스키마가 깔린 DB(배포 DB·기존 로컬)에는 이 파일을 적용한다.
-- schema.sql에도 같은 정의가 반영되어 있어 신규 DB와 최종 상태가 같다.
--
-- 왜: I-1 후보 조회는 2026-07-27에 후보 수 상한이 폐지되어(05 §I-1) 매칭된 상품 전부의 평점을
-- 한 번에 집계한다. 그런데 인덱스가 product_id 하나뿐이라 status·rating을 읽으려고 행마다
-- 테이블을 되짚고 있었다 — EXPLAIN에서 review 쪽이 `Using index`가 아니라 `Using where`로 나온다.
-- 세 컬럼을 인덱스에 담으면 집계가 인덱스만으로 끝난다.
--
-- 반정규화(product.rating_avg) 대신 이걸 택한 이유는 02 D9의 선택 (A)를 유지하기 위해서다.
-- 컬럼을 두면 리뷰 등록·숨김·삭제 세 경로에 갱신이 붙고, 하나라도 새면 조용히 어긋난다.
-- 인덱스는 그 정합 부채가 없다.
--
-- 이름을 그대로 두고 컬럼만 늘린다 — product_id가 여전히 선두라 기존 조회는 전부 그대로 탄다.
-- 중복 인덱스를 남기지 않으려고 추가가 아니라 교체다.
--
-- 쓰기 비용: 리뷰 INSERT/UPDATE 시 인덱스 엔트리가 커진다(TINYINT + VARCHAR(20)). 리뷰 쓰기는
-- 사용자당 드물어 무시 가능한 수준이다.

-- ⚠ DROP과 ADD를 나누면 실패한다 — fk_review_product가 이 인덱스를 필요로 해서
-- "Cannot drop index: needed in a foreign key constraint"가 난다. 한 문장으로 묶으면
-- 교체 후에도 product_id 선두 인덱스가 존재하므로 FK 요건이 끊기지 않는다.
ALTER TABLE review
    DROP INDEX idx_review_product,
    ADD KEY idx_review_product (product_id, status, rating);
