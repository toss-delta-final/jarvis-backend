package com.jarvis.product.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jarvis.product.Product;

/**
 * I-1/I-3 리랭킹용 최소필드 (05 §I-1) — display 필드(가격·이미지·평점)는 제거,
 * 카드 조회(CH-5/P-7) 소관. attributes는 LLM 후처리 필터링용 (02 D7).
 */
public record ProductCandidateResponse(Long productId, String name, String summary,
                                       JsonNode attributes, String categoryName, String brandName) {

    public static ProductCandidateResponse from(Product product, JsonNode attributes,
                                                String categoryName, String brandName) {
        return new ProductCandidateResponse(product.getId(), product.getName(), product.getSummary(),
                attributes, categoryName, brandName);
    }
}
