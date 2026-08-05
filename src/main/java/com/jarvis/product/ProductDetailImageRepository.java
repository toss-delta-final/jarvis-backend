package com.jarvis.product;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductDetailImageRepository extends JpaRepository<ProductDetailImage, Long> {

    /**
     * P-2 상세 조회 — 상품 1건의 상세 이미지를 노출 순서대로 전부 (02 D42).
     *
     * <p>이 테이블에 걸리는 유일한 쿼리다. {@code UNIQUE(product_id, sort_order)}가 선두 컬럼
     * {@code product_id}로 시작해 별도 인덱스 없이 이 조회를 그대로 탄다.
     */
    List<ProductDetailImage> findAllByProductIdOrderBySortOrderAsc(Long productId);
}
