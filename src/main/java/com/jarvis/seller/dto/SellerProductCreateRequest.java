package com.jarvis.seller.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jarvis.product.ProductStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * I-10 상품 등록 (04 §10, 노션 I-10) — name·price·stockQuantity·categoryId 필수 검증은
 * 서비스 소관(422 MISSING_FIELD — bean validation 400 대신), stockQuantity ≥ 0도 서비스(422 INVALID_STOCK).
 * originalPrice 생략 시 price와 동일(무할인), imageUrl 생략 시 플레이스홀더.
 * attributes는 JSON 객체(노션) — 저장 시 서비스가 문자열로 직렬화.
 */
public record SellerProductCreateRequest(
        @Size(max = 200) String name,
        @Min(0) Integer price,
        @Min(0) Integer originalPrice,
        Integer stockQuantity,
        Long categoryId,
        @Size(max = 500) String summary,
        JsonNode attributes,
        String description,
        @Size(max = 500) String imageUrl,
        ProductStatus status) {
}
