package com.jarvis.cart.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** C-2 (04 §3) — 동일 상품+옵션 존재 시 수량 합산(상한 99 클램프) */
public record CartAddRequest(
        @NotNull(message = "상품 ID는 필수입니다.") Long productId,
        Long optionId,
        @NotNull(message = "수량은 필수입니다.")
        @Min(value = 1, message = "수량은 1 이상이어야 합니다.")
        @Max(value = 99, message = "수량은 99 이하여야 합니다.") Integer quantity) {
}
