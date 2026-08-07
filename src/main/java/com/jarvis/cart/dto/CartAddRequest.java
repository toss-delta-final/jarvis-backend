package com.jarvis.cart.dto;

import com.jarvis.recommendation.dto.RecommendationContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * C-2 (04 §3) — 동일 상품+옵션 존재 시 수량 합산(상한 99 클램프)
 *
 * <p>{@code recommendationContext}는 추천 카드에서 담을 때만 FE가 싣는다(선택). 검증에 실패해도
 * 담기는 성공하며 출처만 버려진다 — 규칙은 {@code RecommendationAttributionResolver} 참조.
 */
public record CartAddRequest(
        @NotNull(message = "상품 ID는 필수입니다.") Long productId,
        Long optionId,
        @NotNull(message = "수량은 필수입니다.")
        @Min(value = 1, message = "수량은 1 이상이어야 합니다.")
        @Max(value = 99, message = "수량은 99 이하여야 합니다.") Integer quantity,
        @Valid RecommendationContext recommendationContext) {
}
