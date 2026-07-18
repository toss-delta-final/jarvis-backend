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

/** I-6 (04 §10) — 시계열 0 채움 + 7구간 이동평균 ±30% 이상 감지 */
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
        when(orderItemRepository.sumSellerSales(eq(BRAND_ID), any(), any()))
                .thenReturn(totals(500000, 6, 8));
        when(orderItemRepository.countSellerItemStatus(eq(BRAND_ID), any(), any()))
                .thenReturn(List.of());
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

        SellerSalesResponse response = service.sales(BRAND_ID, "daily", from, to);

        assertThat(response.series()).hasSize(10); // 0 채움 포함 전 구간
        SellerSalesResponse.Point spike = response.series().get(9);
        assertThat(spike.isAnomaly()).isTrue();
        assertThat(spike.deviationPct()).isGreaterThan(30.0);
        assertThat(response.series().get(5).isAnomaly()).isFalse();
    }

    @Test
    @DisplayName("granularity=summary — series 없이 합계·statusCounts만")
    void summaryGranularityOmitsSeries() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        when(orderItemRepository.sumSellerSales(eq(BRAND_ID), any(), any()))
                .thenReturn(totals(300000, 3, 4));
        when(orderItemRepository.countSellerItemStatus(eq(BRAND_ID), any(), any()))
                .thenReturn(List.of());

        SellerSalesResponse response = service.sales(BRAND_ID, "summary", null, null);

        assertThat(response.series()).isNull();
        assertThat(response.totalSales()).isEqualTo(300000);
    }

    @Test
    @DisplayName("잘못된 granularity는 400, 없는 브랜드는 404")
    void invalidInputs() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        when(orderItemRepository.sumSellerSales(eq(BRAND_ID), any(), any()))
                .thenReturn(totals(0, 0, 0));
        when(orderItemRepository.countSellerItemStatus(eq(BRAND_ID), any(), any()))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.sales(BRAND_ID, "hourly", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);

        when(brandRepository.existsById(anyLong())).thenReturn(false);
        assertThatThrownBy(() -> service.sales(999L, "daily", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BRAND_NOT_FOUND);
    }
}
