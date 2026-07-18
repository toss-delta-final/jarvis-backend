package com.jarvis.cart.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** C-3 (04 §3) — 수량 변경(합산 아님) */
public record CartQuantityRequest(
        @NotNull(message = "수량은 필수입니다.")
        @Min(value = 1, message = "수량은 1 이상이어야 합니다.")
        @Max(value = 99, message = "수량은 99 이하여야 합니다.") Integer quantity) {
}
