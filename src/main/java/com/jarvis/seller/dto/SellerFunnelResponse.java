package com.jarvis.seller.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * I-7 구매전환 퍼널 4단 (04 §10) — 1·2단 behavior_events, 3단 checkout_start의
 * properties.productIds 포함 여부(주문서 1회=1), 4단 order_item×product×brand(정본).
 * conversionRate는 직전 단 대비 %(첫 단·직전 0이면 null).
 */
public record SellerFunnelResponse(Long brandId, LocalDate from, LocalDate to, List<Stage> stages) {

    public record Stage(String stage, long count, Double conversionRate) {
    }
}
