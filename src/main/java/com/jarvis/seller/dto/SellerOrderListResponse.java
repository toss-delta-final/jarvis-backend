package com.jarvis.seller.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** S-2 자사 주문 아이템 단위 목록 (04 §7) — 자사 아이템만, 구매자 개인정보 미노출 */
public record SellerOrderListResponse(List<Row> items, int page, int size,
                                      long totalElements, int totalPages) {

    public record Row(Long orderItemId, Long orderId, String orderNo, OffsetDateTime orderedAt,
                      Long productId, String productName, String optionName, int price,
                      int quantity, String status) {
    }
}
