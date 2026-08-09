package com.jarvis.seller.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jarvis.product.ProductStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * I-11 상품 수정 요청 (04 §10, 노션 I-11) — 전 필드 optional(부분 수정),
 * price ≤ originalPrice 교차 검증·stocks[].quantity ≥ 0(422)은 서비스 소관 (02 D28).
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
        /** 2026-08-09 — 구 {@code stockQuantity}(정수) 대체. 실린 옵션만 갱신하는 부분 수정이다 */
        List<StockInput> stocks,
        // 구 필드 — 하위호환. 근거는 SellerProductCreateRequest 참조
        Integer stockQuantity,
        @Size(max = 500) String imageUrl) {

    /** 구 {@code stockQuantity} 하위호환 — 옵션 없는 상품에선 {@code stocks} 한 줄과 같은 뜻이다 (2026-08-09) */
    public List<StockInput> resolvedStocks() {
        if (stocks != null) {
            return stocks;
        }
        return stockQuantity == null ? null : List.of(new StockInput(null, stockQuantity));
    }

    public boolean isEmpty() {
        return name == null && summary == null && attributes == null && description == null
                && price == null && originalPrice == null && status == null
                && resolvedStocks() == null && imageUrl == null;
    }
}
