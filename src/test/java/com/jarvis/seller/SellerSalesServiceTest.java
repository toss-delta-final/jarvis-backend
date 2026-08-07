package com.jarvis.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jarvis.brand.Brand;
import com.jarvis.brand.BrandRepository;
import com.jarvis.global.event.BehaviorEventRepository;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.order.OrderItemRepository;
import com.jarvis.order.OrderStatusLogRepository;
import com.jarvis.product.ProductRepository;
import com.jarvis.seller.dto.SellerSalesResponse;
import com.jarvis.seller.dto.SellerSummaryResponse;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** I-6 (04 §10) — 시계열 0 채움 + 7구간 이동평균 ±30% 이상 감지, from/to 필수(INVALID_PERIOD) */
@ExtendWith(MockitoExtension.class)
class SellerSalesServiceTest {

    private static final Long BRAND_ID = 7L;

    @Mock private OrderItemRepository orderItemRepository;
    @Mock private OrderStatusLogRepository orderStatusLogRepository;
    @Mock private BehaviorEventRepository behaviorEventRepository;
    @Mock private ProductRepository productRepository;
    @Mock private BrandRepository brandRepository;

    @InjectMocks private SellerSalesService service;

    private static OrderItemRepository.SalesTotalsRow totals(long sales, long orders, long qty) {
        return new OrderItemRepository.SalesTotalsRow() {
            public Long getSales() { return sales; }
            public Long getOrders() { return orders; }
            public Long getQuantity() { return qty; }
        };
    }

    private static OrderItemRepository.StatusCountRow status(String bucket, long cnt) {
        return new OrderItemRepository.StatusCountRow() {
            public String getBucket() { return bucket; }
            public Long getCnt() { return cnt; }
        };
    }

    private static OrderItemRepository.PeriodSalesRow period(String period, long sales) {
        return new OrderItemRepository.PeriodSalesRow() {
            public String getPeriod() { return period; }
            public Long getSales() { return sales; }
            public Long getOrders() { return 1L; }
            public Long getQuantity() { return 1L; }
        };
    }

    @Test
    @DisplayName("daily — 빈 날은 0으로 채우고, 이동평균 +30% 초과 스파이크에 isAnomaly (04 §10 I-6)")
    void dailySeriesZeroFillAndAnomaly() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        LocalDate to = LocalDate.of(2026, 7, 18);
        LocalDate from = to.minusDays(9);
        // 매일 10만원 흐름에 마지막 날 100만원 스파이크
        List<OrderItemRepository.PeriodSalesRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            rows.add(period(from.plusDays(i).toString(), 100000));
        }
        rows.add(period(to.toString(), 1000000));
        when(orderItemRepository.sumSellerSalesByPeriod(eq(BRAND_ID), anyString(), any(), any()))
                .thenReturn(rows);

        SellerSalesResponse response = service.sales(BRAND_ID, "daily", new AnalysisPeriod(from, to));

        assertThat(response.series()).hasSize(10); // 0 채움 포함 전 구간
        assertThat(response.series().get(0).date()).isEqualTo(from.toString());
        SellerSalesResponse.Point spike = response.series().get(9);
        assertThat(spike.isAnomaly()).isTrue();
        assertThat(spike.deviationPct()).isGreaterThan(30.0);
        assertThat(response.series().get(5).isAnomaly()).isFalse();
        assertThat(response.config().windowDays()).isEqualTo(7);
        assertThat(response.config().deviationPct()).isEqualTo(30.0);
        // 노션 계약: statusCounts는 summary에서만
        assertThat(response.statusCounts()).isNull();
        assertThat(response.sales()).isNull();
    }

    @Test
    @DisplayName("granularity=summary — series 없이 sales/orderCount/avgDailySales/statusCounts만")
    void summaryGranularityOmitsSeries() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        when(orderItemRepository.sumSellerSales(eq(BRAND_ID), any(), any()))
                .thenReturn(totals(300000, 3, 4));
        when(orderItemRepository.countSellerStatusBuckets(eq(BRAND_ID), any(), any()))
                .thenReturn(List.of(status("PAID", 3), status("CANCELLED", 1)));
        AnalysisPeriod period = AnalysisPeriod.of("2026-07-01", "2026-07-10");

        SellerSalesResponse response = service.sales(BRAND_ID, "summary", period);

        assertThat(response.series()).isNull();
        assertThat(response.config()).isNull();
        assertThat(response.sales()).isEqualTo(300000);
        assertThat(response.orderCount()).isEqualTo(3);
        assertThat(response.avgDailySales()).isEqualTo(30000); // 30만 / 10일
        // 노션 I-6 확정 어휘 4종 고정 — 누락 버킷은 0 채움
        assertThat(response.statusCounts()).containsExactly(
                java.util.Map.entry("PAID", 3L), java.util.Map.entry("CANCELLED", 1L),
                java.util.Map.entry("PAYMENT_FAILED", 0L), java.util.Map.entry("RETURNED", 0L));
    }

    @Test
    @DisplayName("잘못된 granularity는 400, 없는 브랜드는 404")
    void invalidInputs() {
        AnalysisPeriod period = AnalysisPeriod.of("2026-07-01", "2026-07-10");
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        assertThatThrownBy(() -> service.sales(BRAND_ID, "hourly", period))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);

        when(brandRepository.existsById(anyLong())).thenReturn(false);
        assertThatThrownBy(() -> service.sales(999L, "daily", period))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BRAND_NOT_FOUND);
    }

    @Test
    @DisplayName("S-1 summary — 전 블록 구성 + orderStatus 6종/activeTotal + 어제 대비 changeRate (노션 S-1)")
    void summaryBuildsAllBlocksWithYesterdayChangeRate() {
        when(orderItemRepository.countSellerItemsByStatus(BRAND_ID))
                .thenReturn(List.of(status("ORDERED", 3), status("SHIPPING", 2), status("CANCELLED", 1)));
        when(orderStatusLogRepository.avgSellerDeliverySeconds(BRAND_ID)).thenReturn(155520.0); // 1.8일
        // today() → sumSellerSales(오늘), 그다음 sumSellerSales(어제)
        when(orderItemRepository.sumSellerSales(eq(BRAND_ID), any(), any()))
                .thenReturn(totals(120000, 4, 0), totals(100000, 2, 0));
        when(behaviorEventRepository.countActiveVisitors(eq(BRAND_ID), any())).thenReturn(42L);
        when(orderItemRepository.sumSellerSalesByPeriod(eq(BRAND_ID), anyString(), any(), any()))
                .thenReturn(List.of());
        when(productRepository.findLowStock(eq(BRAND_ID), anyInt())).thenReturn(List.of());
        Brand brand = mock(Brand.class);
        when(brand.getId()).thenReturn(BRAND_ID);

        SellerSummaryResponse res = service.summary(brand, null, null, null, null);

        assertThat(res.orderStatus().counts())
                .containsEntry("ORDERED", 3L).containsEntry("SHIPPING", 2L)
                .containsEntry("DELIVERED", 0L).containsEntry("CONFIRMED", 0L)
                .containsEntry("CANCELLED", 1L).containsEntry("RETURNED", 0L);
        assertThat(res.orderStatus().activeTotal()).isEqualTo(5); // 3+2, CANCELLED 제외
        assertThat(res.orderStatus().avgDeliveryDays()).isEqualTo(1.8);
        assertThat(res.today().sales()).isEqualTo(120000);
        assertThat(res.today().avgOrderValue()).isEqualTo(30000); // 120000/4
        assertThat(res.today().activeVisitors()).isEqualTo(42);
        assertThat(res.today().salesChangeRate()).isEqualTo(20.0);      // (120000-100000)/100000
        assertThat(res.today().orderCountChangeRate()).isEqualTo(100.0); // (4-2)/2
        assertThat(res.period().from()).isEqualTo(res.period().to());   // 기본 오늘~오늘
        assertThat(res.salesTrend().points()).hasSize(7);               // trendDays 기본 7
        assertThat(res.salesTrend().total()).isZero();
    }

    @Test
    @DisplayName("S-1 summary — 범위 밖 파라미터·날짜 형식 오류·from>to는 400 SELLER_INVALID_PARAM (노션 S-1)")
    void summaryRejectsInvalidParams() {
        Brand brand = mock(Brand.class);
        assertThatThrownBy(() -> service.summary(brand, null, null, 0, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SELLER_INVALID_PARAM);
        assertThatThrownBy(() -> service.summary(brand, null, null, null, 91))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SELLER_INVALID_PARAM);
        assertThatThrownBy(() -> service.summary(brand, "2026-13-99", null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SELLER_INVALID_PARAM);
        assertThatThrownBy(() -> service.summary(brand, "2026-07-10", "2026-07-01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SELLER_INVALID_PARAM);
    }

    @Test
    @DisplayName("from/to 누락·형식 오류·from>to는 400 INVALID_PERIOD (노션 I-6)")
    void invalidPeriod() {
        assertThatThrownBy(() -> AnalysisPeriod.of(null, "2026-07-10"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PERIOD);
        assertThatThrownBy(() -> AnalysisPeriod.of("2026-07-01", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PERIOD);
        assertThatThrownBy(() -> AnalysisPeriod.of("2026-13-99", "2026-07-10"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PERIOD);
        assertThatThrownBy(() -> AnalysisPeriod.of("2026-07-10", "2026-07-01"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PERIOD);
    }

    // ---- AI 추천 성과 (「AI 추천 성과」 2026-08-06 제안 · 2026-08-07 구현) ----

    private static OrderItemRepository.AttributionRow attribution(long confirmed, long estimated,
                                                                  long orders) {
        OrderItemRepository.AttributionRow row = mock(OrderItemRepository.AttributionRow.class);
        when(row.getConfirmedSales()).thenReturn(confirmed);
        when(row.getEstimatedSales()).thenReturn(estimated);
        when(row.getAiOrderCount()).thenReturn(orders);
        return row;
    }

    private static OrderItemRepository.CoverageRow coverage(long paid, long observed) {
        OrderItemRepository.CoverageRow row = mock(OrderItemRepository.CoverageRow.class);
        when(row.getPaidOrders()).thenReturn(paid);
        // 분모가 0이면 관측 수를 읽기 전에 null로 끊는다 — 그 경로에선 이 스터빙이 안 쓰인다
        lenient().when(row.getObservedOrders()).thenReturn(observed);
        return row;
    }

    private static BehaviorEventRepository.RecommendationFunnelRow funnel(Long impression,
                                                                         Long click, Long cart) {
        BehaviorEventRepository.RecommendationFunnelRow row =
                mock(BehaviorEventRepository.RecommendationFunnelRow.class);
        when(row.getImpression()).thenReturn(impression);
        when(row.getClick()).thenReturn(click);
        when(row.getAddToCart()).thenReturn(cart);
        return row;
    }

    /** 대시보드 나머지 블록은 이 테스트들의 관심사가 아니라 최소만 채운다 */
    private Brand stubDashboardBasics() {
        lenient().when(orderItemRepository.countSellerItemsByStatus(BRAND_ID)).thenReturn(List.of());
        lenient().when(orderStatusLogRepository.avgSellerDeliverySeconds(BRAND_ID)).thenReturn(null);
        lenient().when(behaviorEventRepository.countActiveVisitors(eq(BRAND_ID), any())).thenReturn(0L);
        lenient().when(orderItemRepository.sumSellerSalesByPeriod(eq(BRAND_ID), anyString(), any(),
                any())).thenReturn(List.of());
        lenient().when(productRepository.findLowStock(eq(BRAND_ID), anyInt())).thenReturn(List.of());
        Brand brand = mock(Brand.class);
        lenient().when(brand.getId()).thenReturn(BRAND_ID);
        return brand;
    }

    // 직접 매출은 총액에서 차감해 만든다 — 그래야 합이 총액과 달라질 수 없다
    @Test
    @DisplayName("S-1 AI 성과 — confirmed+estimated+direct = totalSales 항등식이 성립한다")
    void aiAttributionIdentityHolds() {
        Brand brand = stubDashboardBasics();
        OrderItemRepository.AttributionRow attr = attribution(820000, 360000, 34);
        OrderItemRepository.CoverageRow cov = coverage(100, 93);
        BehaviorEventRepository.RecommendationFunnelRow fun = funnel(8200L, 1490L, 610L);
        when(orderItemRepository.aggregateAiAttribution(eq(BRAND_ID), any(), any(), anyInt()))
                .thenReturn(attr);
        when(orderItemRepository.sumSellerSales(eq(BRAND_ID), any(), any()))
                .thenReturn(totals(4600000, 40, 50), totals(0, 0, 0), totals(4600000, 40, 50));
        when(behaviorEventRepository.aggregateRecommendationFunnel(eq(BRAND_ID), any(), any()))
                .thenReturn(fun);
        when(orderItemRepository.aggregateCollectionCoverage(eq(BRAND_ID), any(), any()))
                .thenReturn(cov);
        when(orderItemRepository.countSellerPurchaseOrders(eq(BRAND_ID), any(), any(), any()))
                .thenReturn(61L);

        SellerSummaryResponse.AiAttribution ai =
                service.summary(brand, null, null, null, null).aiAttribution();

        assertThat(ai.confirmedSales() + ai.estimatedSales() + ai.directSales())
                .isEqualTo(ai.totalSales());
        assertThat(ai.aiSales()).isEqualTo(1180000);
        assertThat(ai.aiShare()).isEqualTo(25.7);          // 1180000/4600000
        assertThat(ai.aiOrderCount()).isEqualTo(34);
        assertThat(ai.windowDays()).isEqualTo(7);
        assertThat(ai.policyVersion()).isEqualTo("v1");
        assertThat(ai.funnel().purchase()).isEqualTo(61);  // 구매 단만 주문 정본에서 센다
        assertThat(ai.coverage()).isEqualTo(0.93);
    }

    // "AI 매출 0%"가 아니라 "계산할 모수가 없다" — I-16 churnRate와 같은 규칙이다
    @Test
    @DisplayName("S-1 AI 성과 — 매출이 0이면 aiShare는 0이 아니라 null")
    void aiShareIsNullWithoutSales() {
        Brand brand = stubDashboardBasics();
        OrderItemRepository.AttributionRow attr = attribution(0, 0, 0);
        OrderItemRepository.CoverageRow cov = coverage(0, 0);
        BehaviorEventRepository.RecommendationFunnelRow fun = funnel(null, null, null);
        when(orderItemRepository.aggregateAiAttribution(eq(BRAND_ID), any(), any(), anyInt()))
                .thenReturn(attr);
        when(orderItemRepository.sumSellerSales(eq(BRAND_ID), any(), any()))
                .thenReturn(totals(0, 0, 0));
        when(behaviorEventRepository.aggregateRecommendationFunnel(eq(BRAND_ID), any(), any()))
                .thenReturn(fun);
        when(orderItemRepository.aggregateCollectionCoverage(eq(BRAND_ID), any(), any()))
                .thenReturn(cov);
        when(orderItemRepository.countSellerPurchaseOrders(eq(BRAND_ID), any(), any(), any()))
                .thenReturn(0L);

        SellerSummaryResponse.AiAttribution ai =
                service.summary(brand, null, null, null, null).aiAttribution();

        assertThat(ai.aiShare()).isNull();
        assertThat(ai.coverage()).isNull();
        // SUM은 대상 행이 없으면 0이 아니라 NULL이다 — 그대로 내보내면 FE가 터진다
        assertThat(ai.funnel().impression()).isZero();
    }

    // 귀속 집계 하나 때문에 대시보드 전체가 500이 되면 안 된다
    @Test
    @DisplayName("S-1 AI 성과 — 집계가 실패해도 이 블록만 null이고 나머지는 정상 응답한다")
    void aiAttributionFailureIsIsolated() {
        Brand brand = stubDashboardBasics();
        when(orderItemRepository.sumSellerSales(eq(BRAND_ID), any(), any()))
                .thenReturn(totals(0, 0, 0));
        when(orderItemRepository.aggregateAiAttribution(eq(BRAND_ID), any(), any(), anyInt()))
                .thenThrow(new RuntimeException("귀속 쿼리 실패"));

        SellerSummaryResponse response = service.summary(brand, null, null, null, null);

        assertThat(response.aiAttribution()).isNull();
        assertThat(response.orderStatus()).isNotNull();
        assertThat(response.salesTrend()).isNotNull();
    }
}
