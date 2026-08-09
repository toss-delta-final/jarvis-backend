package com.jarvis.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jarvis.global.event.BehaviorEventRepository;
import com.jarvis.order.OrderItemRepository;
import com.jarvis.seller.dto.SellerSummaryResponse;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AI 추천 성과 산식 (「AI 추천 성과」 2026-08-06 제안 · 2026-08-07 구현).
 *
 * <p>실패 시 이 블록만 비우는 격리는 호출부 계약이라 {@link SellerSalesServiceTest}에서 본다.
 */
@ExtendWith(MockitoExtension.class)
class SellerAttributionServiceTest {

    private static final Long BRAND_ID = 7L;
    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 8);

    @Mock private OrderItemRepository orderItemRepository;
    @Mock private BehaviorEventRepository behaviorEventRepository;

    @InjectMocks private SellerAttributionService service;

    private static OrderItemRepository.SalesTotalsRow totals(long sales, long orders, long qty) {
        return new OrderItemRepository.SalesTotalsRow() {
            public Long getSales() { return sales; }
            public Long getOrders() { return orders; }
            public Long getQuantity() { return qty; }
        };
    }

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

    // 직접 매출은 총액에서 차감해 만든다 — 그래야 합이 총액과 달라질 수 없다
    @Test
    @DisplayName("S-1 AI 성과 — confirmed+estimated+direct = totalSales 항등식이 성립한다")
    void aiAttributionIdentityHolds() {
        // 스텁 행은 미리 만든다 — 헬퍼가 내부에서 when()을 쓰므로 when().thenReturn() 안에
        // 인라인하면 중첩 스터빙(UnfinishedStubbingException)이 된다
        OrderItemRepository.AttributionRow attr = attribution(820000, 360000, 34);
        OrderItemRepository.CoverageRow cov = coverage(100, 93);
        BehaviorEventRepository.RecommendationFunnelRow fun = funnel(8200L, 1490L, 610L);
        when(orderItemRepository.aggregateAiAttribution(eq(BRAND_ID), any(), any(), anyInt()))
                .thenReturn(attr);
        when(orderItemRepository.sumSellerSales(eq(BRAND_ID), any(), any()))
                .thenReturn(totals(4600000, 40, 50));
        when(behaviorEventRepository.aggregateRecommendationFunnel(eq(BRAND_ID), any(), any()))
                .thenReturn(fun);
        when(orderItemRepository.aggregateCollectionCoverage(eq(BRAND_ID), any(), any()))
                .thenReturn(cov);
        when(orderItemRepository.countSellerPurchaseOrders(eq(BRAND_ID), any(), any(), any()))
                .thenReturn(61L);

        SellerSummaryResponse.AiAttribution ai = service.aggregate(BRAND_ID, FROM, TO);

        assertThat(ai.confirmedSales() + ai.estimatedSales() + ai.directSales())
                .isEqualTo(ai.totalSales());
        assertThat(ai.aiSales()).isEqualTo(1180000);
        assertThat(ai.aiShare()).isEqualTo(25.7);          // 1180000/4600000
        assertThat(ai.aiOrderCount()).isEqualTo(34);
        assertThat(ai.windowDays()).isEqualTo(7);
        assertThat(ai.policyVersion()).isEqualTo("v2");
        assertThat(ai.funnel().purchase()).isEqualTo(61);  // 구매 단만 주문 정본에서 센다
        assertThat(ai.coverage()).isEqualTo(0.93);
    }

    // "AI 매출 0%"가 아니라 "계산할 모수가 없다" — I-16 churnRate와 같은 규칙이다
    @Test
    @DisplayName("S-1 AI 성과 — 매출이 0이면 aiShare는 0이 아니라 null")
    void aiShareIsNullWithoutSales() {
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

        SellerSummaryResponse.AiAttribution ai = service.aggregate(BRAND_ID, FROM, TO);

        assertThat(ai.aiShare()).isNull();
        assertThat(ai.coverage()).isNull();
        // SUM은 대상 행이 없으면 0이 아니라 NULL이다 — 그대로 내보내면 FE가 터진다
        assertThat(ai.funnel().impression()).isZero();
    }
}
