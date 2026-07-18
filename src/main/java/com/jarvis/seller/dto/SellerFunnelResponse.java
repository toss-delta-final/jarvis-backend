package com.jarvis.seller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;

/**
 * I-7 구매전환 퍼널 4단 (04 §10, 노션 I-7) — 1·2단 behavior_events, 3단 checkout_start의
 * properties.productIds 포함 여부(주문서 1회=1), 4단 purchase_complete는 order_item×product×brand(정본).
 * conversionRates는 소수(fraction) — 해당 단 count가 null이면 그 전환율도 null.
 */
public record SellerFunnelResponse(Long brandId, LocalDate from, LocalDate to, List<Stage> stages,
                                   ConversionRates conversionRates) {

    /**
     * source = "events"(behavior_events) | "orders"(주문 정본).
     * computable은 3단(checkout_start) 전용 — v1(productIds 이전) 구간이면 count null·false.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Stage(String stage, Long count, String source, Boolean computable) {
    }

    public record ConversionRates(Double viewToCart, Double cartToCheckout,
                                  Double checkoutToPurchase, Double overall) {
    }
}
