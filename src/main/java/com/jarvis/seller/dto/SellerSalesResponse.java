package com.jarvis.seller.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * I-6 매출 시계열 (04 §10) — 집계만 반환(raw 주문 로그 미노출 — 05 §I-6).
 * isAnomaly·deviationPct는 직전 7개 구간 이동평균 대비 ±30% (구간 3개 미만이면 null/false).
 * granularity=summary면 series는 null(합계·statusCounts만).
 */
public record SellerSalesResponse(Long brandId, String granularity, LocalDate from, LocalDate to,
                                  long totalSales, long totalOrderCount, long totalSalesCount,
                                  Map<String, Long> statusCounts, List<Point> series) {

    public record Point(String period, long sales, long orderCount, long salesCount,
                        Double deviationPct, boolean isAnomaly) {
    }
}
