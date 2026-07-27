package com.jarvis.product.dto;

import com.jarvis.product.Product;

/**
 * I-1/I-3 후보 한 행 — 상품 + 평점 집계 (05 §I-1). 후보 수 상한이 폐지되어(2026-07-27)
 * id IN 배치 집계를 쓸 수 없으므로 검색 쿼리에서 함께 집계한다. 평점은 여전히 저장하지 않는다(02 D9).
 * 리뷰가 없으면 SQL AVG 규약대로 {@code ratingAverage}가 null — 0으로 나누는 경로는 만들지 않는다.
 */
public record CandidateRow(Product product, Long reviewCount, Double ratingAverage) {
}
