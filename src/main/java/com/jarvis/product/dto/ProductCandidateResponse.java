package com.jarvis.product.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jarvis.product.Product;
import com.jarvis.review.dto.RatingStats;

/**
 * I-1/I-3 후보 필드 (05 §I-1) — 리랭킹 계산 입력인 price·rating·reviewCount는 포함하고(2026-07-27),
 * 나머지 display 필드(originalPrice·imageUrl·options)는 카드 조회(CH-5/P-7) 소관.
 * attributes는 LLM 후처리 필터링용 (02 D7).
 */
public record ProductCandidateResponse(Long productId, String name, String summary,
                                       JsonNode attributes, String categoryName, String brandName,
                                       int price, double rating, long reviewCount) {

    public static ProductCandidateResponse from(Product product, JsonNode attributes,
                                                String categoryName, String brandName,
                                                RatingStats stats) {
        return new ProductCandidateResponse(product.getId(), product.getName(), product.getSummary(),
                attributes, categoryName, brandName, product.getPrice(),
                stats.average(), stats.count());
    }
}
