package com.jarvis.seller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * I-13 행동 이벤트 집계 (노션 I-13) — 판매자 스코프의 귀속 경로는 이벤트마다 다르다:
 * product_view·add_to_cart는 behavior_events.product_id→product→brand, checkout_start는
 * properties.productIds JSON, purchaseComplete는 order_item×product×brand(주문 정본, #62).
 * shape가 groupBy로 갈린다: product → rows+total, eventType → counts, date → series — NON_NULL로 상호 배제.
 * counts 키는 event_type camelCase(product_view→productView).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SellerEventsResponse(Long brandId, LocalDate from, LocalDate to, String groupBy,
                                   List<ProductRow> rows, Integer total,
                                   Map<String, Long> counts, List<Map<String, Object>> series) {

    public static SellerEventsResponse ofProduct(Long brandId, LocalDate from, LocalDate to,
                                                 List<ProductRow> rows) {
        return new SellerEventsResponse(brandId, from, to, "product", rows, rows.size(), null, null);
    }

    public static SellerEventsResponse ofEventType(Long brandId, LocalDate from, LocalDate to,
                                                   Map<String, Long> counts) {
        return new SellerEventsResponse(brandId, from, to, "eventType", null, null, counts, null);
    }

    public static SellerEventsResponse ofDate(Long brandId, LocalDate from, LocalDate to,
                                              List<Map<String, Object>> series) {
        return new SellerEventsResponse(brandId, from, to, "date", null, null, null, series);
    }

    /** viewToCartRate = addToCart/productView(분모 0이거나 두 타입이 조회 대상 밖이면 null) */
    public record ProductRow(Long productId, String productName, Map<String, Long> counts,
                             Double viewToCartRate, Long uniqueVisitors) {
    }
}
