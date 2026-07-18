package com.jarvis.seller.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** I-15 상품 변경 이력 (04 §10) — 품절 신호 = STOCK 변경의 newValue "0" */
public record SellerProductChangesResponse(Long brandId, LocalDate from, LocalDate to,
                                           List<Item> items) {

    public record Item(Long productId, String changeType, String oldValue, String newValue,
                       OffsetDateTime createdAt) {
    }
}
