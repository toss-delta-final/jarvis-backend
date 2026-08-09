package com.jarvis.seller.dto;

import com.jarvis.global.response.StringId;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * S-1 자사 대시보드 (노션 S-1, 2026-07-21 화면 확정본) — 진입 1회 호출로 전 블록을 덮는다.
 * 전 구간 brandId는 JWT 도출값을 WHERE에 고정(클라이언트 주장 무시, IDOR). 금액은 KRW 정수, 비율만 소수 1자리.
 */
public record SellerSummaryResponse(Period period, OrderStatus orderStatus, Today today,
                                    SalesTrend salesTrend, LowStock lowStock,
                                    AiAttribution aiAttribution) {

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

    /**
     * 재고 부족 알림 — ON_SALE 중 재고 ≤ threshold, 재고 오름차순.
     *
     * <p><b>2026-08-09 — 판정이 옵션 단위</b>(02 D33 개정). {@code count}는 재고 부족인
     * <b>옵션의 개수</b>이지 상품 수가 아니다 — 한 상품의 여러 옵션이 동시에 부족하면 여러 줄로 나온다.
     * 상품 단위로 묶지 않는 이유는 판매자가 채워 넣을 대상이 상품이 아니라 옵션이기 때문이다.
     */
    public record LowStock(int threshold, int count, List<Item> items) {

        /** 옵션 없는 상품은 optionId·optionName이 둘 다 null이다 */
        public record Item(@StringId Long productId, String name, String imageUrl,
                           @StringId Long optionId, String optionName, int stockQuantity) {
        }
    }

    // ProductMetric(products[])은 2026-08-06 제거 — 소비처가 0건이었고, I-13 groupBy=product가
    // 같은 정보를 더 풍부하게 준다(salesQuantity·체류시간 포함). FE 타입 정의 정리는 그쪽 몫이다.

    /**
     * AI 추천 경유 매출 (「AI 추천 성과」 2026-08-06 제안 · 2026-08-07 구현) — 판정 단위는
     * {@code order_item} 한 줄이다. 한 주문에 추천 상품과 직접 발견 상품이 섞이므로 주문 단위로는
     * 못 나눈다. 집계가 실패하면 이 블록만 {@code null}로 내려간다(대시보드 전체를 죽이지 않는다).
     *
     * <p><b>합계 항등식</b>: {@code confirmedSales + estimatedSales + directSales == totalSales}.
     * AI 매출만 산출하고 직접 매출은 총액에서 <b>차감</b>해 만들므로 어긋날 수 없다.
     *
     * <p><b>이 지표는 기여도지 증분이 아니다</b> — 모든 탐색 경로에 AI가 개입해 대조군이 없으므로
     * "AI 덕분에 매출이 올랐다"는 인과 주장에 쓸 수 없다. 과소 요인(이벤트 유실·창 초과 구매·게스트
     * 쿠키 만료)이 우세해 <b>하한값</b>으로 읽어야 한다.
     *
     * @param aiShare       전체 매출 중 AI 경유 비율(%). 분모가 0이면 null — 0%가 아니라 "계산 불가"다
     * @param coverage      결제 이벤트 관측률. 이 값만큼 AI 매출도 과소집계됐다고 읽는다. 분모 0이면 null
     * @param policyVersion 귀속 창·이벤트 범위를 바꾸면 올린다 — 같은 기간 숫자가 달라지므로
     *                      과거 보고서와의 비교 기준을 남겨야 한다
     */
    public record AiAttribution(String policyVersion, int windowDays,
                                long totalSales, long aiSales,
                                long confirmedSales, long estimatedSales, long directSales,
                                Double aiShare, long aiOrderCount,
                                Funnel funnel, Double coverage) {

        /** 노출 → 클릭 → 담기 → 구매. 구매만 주문 정본에서 센다(이벤트로는 상품 귀속 불가) */
        public record Funnel(long impression, long click, long addToCart, long purchase) {
        }
    }
}
