package com.jarvis.seller.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * S-1 자사 요약 (04 §7) — 매출·판매수 = PAID 주문의 order_item 중
 * PENDING/CANCELLED/RETURNED 제외(처리중 포함). topProducts는 판매수·조회수 상위 10개.
 */
public record SellerSummaryResponse(Long brandId, String brandName, LocalDate from, LocalDate to,
                                    long totalSales, long orderCount, long salesCount,
                                    List<ProductMetric> topProducts) {

    public record ProductMetric(Long productId, String name, long viewCount, long cartCount,
                                long salesCount) {
    }
}
