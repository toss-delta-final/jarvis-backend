package com.jarvis.seller.dto;

import jakarta.validation.constraints.Min;

/**
 * I-10·I-11의 {@code stocks[]} 원소 (노션 I-10·I-11, 2026-08-09 — 구 {@code stockQuantity} 정수 대체).
 *
 * <p><b>옵션이 없는 상품도 같은 모양</b>으로 온다 — {@code optionId}가 {@code null}인 한 줄.
 * 옵션 유무로 필드를 가르지 않는 이유는 그러면 같은 필드가 상품마다 다른 뜻이 되고,
 * 읽는 쪽이 매번 분기해야 하기 때문이다(02 D33 개정에서 컬럼에 대해 내린 판단과 같다).
 *
 * <p>{@code quantity} 음수·소속 아닌 {@code optionId}는 422 {@code INVALID_STOCK} — 검증은 서비스 소관.
 */
public record StockInput(Long optionId, @Min(0) Integer quantity) {
}
