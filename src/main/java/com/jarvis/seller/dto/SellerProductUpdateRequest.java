package com.jarvis.seller.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jarvis.product.ProductStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * I-11 상품 수정 요청 (04 §10, 노션 I-11) — 전 필드 optional(부분 수정),
 * price ≤ originalPrice 교차 검증·stockQuantity ≥ 0(422)은 서비스 소관 (02 D28).
 * attributes는 JSON 객체(노션) — 저장 시 서비스가 문자열로 직렬화.
 */
public record SellerProductUpdateRequest(
        @Size(max = 200) String name,
        @Size(max = 500) String summary,
        JsonNode attributes,
        String description,
        @Min(0) Integer price,
        @Min(0) Integer originalPrice,
        ProductStatus status,
        Integer stockQuantity,
        @Size(max = 500) String imageUrl) {

    public boolean isEmpty() {
        return name == null && summary == null && attributes == null && description == null
                && price == null && originalPrice == null && status == null && stockQuantity == null
                && imageUrl == null;
    }
}
