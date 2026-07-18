package com.jarvis.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.jarvis.brand.BrandRepository;
import com.jarvis.global.event.BehaviorEventRepository;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.order.OrderItemRepository;
import com.jarvis.product.ProductRepository;
import com.jarvis.seller.dto.SellerSalesResponse;
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
}
