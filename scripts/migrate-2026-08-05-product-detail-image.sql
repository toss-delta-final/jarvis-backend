-- 상품 상세 이미지 테이블 신설 (02 D42 · P-2 detailImages)
--
-- schema.sql은 최초 생성 전용(재실행 불가)이라, 이미 스키마가 깔린 DB(배포 DB·기존 로컬)에는 이 파일을 적용한다.
-- schema.sql에도 같은 정의가 반영되어 있어 신규 DB와 최종 상태가 같다.
--
-- 행 데이터는 여기 없다 — 배포 담당이 업로드 매니페스트로 INSERT SQL을 따로 만들어 적재한다(55,887행).
-- 시드를 재적재할 때는 이 테이블도 함께 비우고 다시 넣는다.

CREATE TABLE IF NOT EXISTS product_detail_image (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    product_id  BIGINT       NOT NULL,
    sort_order  INT          NOT NULL,               -- 0-based 노출 순서. 파일명이 아니라 이 값이 정렬 기준 (확장자 혼재로 문자열 정렬 불가)
    image_key   VARCHAR(255) NOT NULL,               -- 버킷 내 경로만: products/{productId}/detail/000.jpg — 호스트는 app.image.base-url
    created_at  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_detail_image (product_id, sort_order),
    CONSTRAINT fk_product_detail_image_product FOREIGN KEY (product_id)
        REFERENCES product (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
