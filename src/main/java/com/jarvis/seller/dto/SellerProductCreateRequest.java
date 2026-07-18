package com.jarvis.seller.dto;

import com.jarvis.product.ProductStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * I-10 상품 등록 (04 §10) — name·price·stockQuantity 필수(명세) + categoryId(DB 필수·D26②).
 * originalPrice 생략 시 price와 동일(무할인), imageUrl 생략 시 플레이스홀더.
 */
public record SellerProductCreateRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull @Min(0) Integer price,
        @Min(0) Integer originalPrice,
        @NotNull @Min(0) Integer stockQuantity,
        @NotNull Long categoryId,
        @Size(max = 500) String summary,
        String attributes,
        String description,
        @Size(max = 500) String imageUrl,
        ProductStatus status) {
}
