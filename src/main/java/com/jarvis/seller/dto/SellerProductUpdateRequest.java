package com.jarvis.seller.dto;

import com.jarvis.product.ProductStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * S-5/I-11 공용 수정 요청 (04 §7·§10) — 전 필드 optional(부분 수정),
 * price ≤ originalPrice 교차 검증은 서비스 소관 (02 D28).
 */
public record SellerProductUpdateRequest(
        @Size(max = 200) String name,
        @Size(max = 500) String summary,
        String attributes,
        String description,
        @Min(0) Integer price,
        @Min(0) Integer originalPrice,
        ProductStatus status,
        @Min(0) Integer stockQuantity) {

    public boolean isEmpty() {
        return name == null && summary == null && attributes == null && description == null
                && price == null && originalPrice == null && status == null && stockQuantity == null;
    }
}
