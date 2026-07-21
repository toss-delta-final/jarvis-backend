package com.jarvis.seller.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * S-1 자사 대시보드 (노션 S-1, 2026-07-21 화면 확정본) — 진입 1회 호출로 전 블록을 덮는다.
 * 전 구간 brandId는 JWT 도출값을 WHERE에 고정(클라이언트 주장 무시, IDOR). 금액은 KRW 정수, 비율만 소수 1자리.
 */
public record SellerSummaryResponse(Period period, OrderStatus orderStatus, Today today,
                                    SalesTrend salesTrend, LowStock lowStock,
                                    List<ProductMetric> products) {

    public record Period(LocalDate from, LocalDate to) {
    }

    /**
     * 오늘 할 일 — counts는 order_item.status 6종(자사 스코프, 현재 스냅샷). activeTotal은 CANCELLED·RETURNED
     * 제외 합. avgDeliveryDays는 order_status_logs SHIPPING→DELIVERED 평균(값 없으면 null, 모의 배송 값).
     */
    public record OrderStatus(Map<String, Long> counts, long activeTotal, Double avgDeliveryDays) {
    }

    /** 오늘 지표 — 항상 오늘(자정~현재). *ChangeRate는 어제 하루 대비(어제 0이면 null → FE "—"). */
    public record Today(long sales, long orderCount, long avgOrderValue, long activeVisitors,
                        Double salesChangeRate, Double orderCountChangeRate,
                        Double avgOrderValueChangeRate) {
    }

    /** 매출 추이 — to 기준 trendDays 구간, 매출 0인 날도 채워 반환(FE 축 어긋남 방지). total = points 합. */
    public record SalesTrend(long total, List<Point> points) {

        public record Point(LocalDate date, long sales) {
        }
    }

    /** 재고 부족 알림 — ON_SALE 중 재고 ≤ threshold, 재고 오름차순. */
    public record LowStock(int threshold, int count, List<Item> items) {

        public record Item(Long productId, String name, String imageUrl, int stockQuantity) {
        }
    }

    /** (화면 없음, AI·타 화면 소비) 상품별 퍼널 — 조회수·담김수 = behavior_events, 판매수 = order_item 집계. */
    public record ProductMetric(Long productId, String name, long viewCount, long cartCount,
                                long salesCount) {
    }
}
