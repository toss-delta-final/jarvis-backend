package com.jarvis.seller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * I-6 매출 시계열 (04 §10) — 집계만 반환(raw 주문 로그 미노출 — 05 §I-6).
 * isAnomaly·deviationPct는 직전 7개 구간 이동평균 대비 ±30% (구간 3개 미만이면 null/false).
 * 노션 계약상 shape가 갈린다: daily/weekly/monthly는 series+config,
 * granularity=summary는 sales/orderCount/avgDailySales/statusCounts만 — NON_NULL로 상호 배제.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SellerSalesResponse(Long brandId, String granularity, LocalDate from, LocalDate to,
                                  Long sales, Long orderCount, Long avgDailySales,
                                  Map<String, Long> statusCounts,
                                  List<Point> series, Config config) {

    public static SellerSalesResponse ofSeries(Long brandId, String granularity,
                                               LocalDate from, LocalDate to,
                                               List<Point> series, Config config) {
        return new SellerSalesResponse(brandId, granularity, from, to,
                null, null, null, null, series, config);
    }

    public static SellerSalesResponse ofSummary(Long brandId, LocalDate from, LocalDate to,
                                                long sales, long orderCount, long avgDailySales,
                                                Map<String, Long> statusCounts) {
        return new SellerSalesResponse(brandId, "summary", from, to,
                sales, orderCount, avgDailySales, statusCounts, null, null);
    }

    public record Point(String date, long sales, long orderCount, long salesCount,
                        Double deviationPct, boolean isAnomaly) {
    }

    public record Config(int windowDays, double deviationPct) {
    }
}
