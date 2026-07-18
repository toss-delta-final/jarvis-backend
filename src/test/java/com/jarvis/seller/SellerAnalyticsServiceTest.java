package com.jarvis.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.brand.BrandRepository;
import com.jarvis.global.event.BehaviorEvent;
import com.jarvis.global.event.BehaviorEventRepository;
import com.jarvis.member.AccountEventLogRepository;
import com.jarvis.order.OrderItemRepository;
import com.jarvis.order.OrderStatusLogRepository;
import com.jarvis.product.Product;
import com.jarvis.product.ProductChangeLogRepository;
import com.jarvis.product.ProductRepository;
import com.jarvis.seller.dto.SellerChurnResponse;
import com.jarvis.seller.dto.SellerFunnelResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** I-7 퍼널 3단 판정 + I-8 IP 마스킹 + I-16 이탈 판정 (04 §10) */
@ExtendWith(MockitoExtension.class)
class SellerAnalyticsServiceTest {

    private static final Long BRAND_ID = 7L;

    @Mock private BehaviorEventRepository behaviorEventRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private OrderStatusLogRepository orderStatusLogRepository;
    @Mock private ProductChangeLogRepository productChangeLogRepository;
    @Mock private AccountEventLogRepository accountEventLogRepository;
    @Mock private ProductRepository productRepository;
    @Mock private BrandRepository brandRepository;

    private SellerAnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new SellerAnalyticsService(behaviorEventRepository, orderItemRepository,
                orderStatusLogRepository, productChangeLogRepository, accountEventLogRepository,
                productRepository, brandRepository, new ObjectMapper());
    }

    private static BehaviorEventRepository.TypeCountRow typeCount(String type, long cnt) {
        return new BehaviorEventRepository.TypeCountRow() {
            public String getEventType() { return type; }
            public Long getCnt() { return cnt; }
        };
    }

    private static BehaviorEvent checkoutEvent(String properties) {
        BehaviorEvent event = mock(BehaviorEvent.class);
        when(event.getProperties()).thenReturn(properties);
        return event;
    }

    private static OrderItemRepository.BuyerRow buyer(long memberId, long orders, long spent) {
        return new OrderItemRepository.BuyerRow() {
            public Long getMemberId() { return memberId; }
            public Long getOrderCount() { return orders; }
            public Long getTotalSpent() { return spent; }
        };
    }

    private static AccountEventLogRepository.LastLoginRow lastLogin(long memberId, LocalDateTime at) {
        return new AccountEventLogRepository.LastLoginRow() {
            public Long getMemberId() { return memberId; }
            public LocalDateTime getLastLogin() { return at; }
        };
    }

    @Test
    @DisplayName("I-7 3단 — checkout_start의 productIds에 자사 상품이 포함된 건만 센다 (02 §4)")
    void funnelCountsBrandCheckouts() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        when(behaviorEventRepository.countSellerFunnelEvents(eq(BRAND_ID), any(), any()))
                .thenReturn(List.of(typeCount("product_view", 100), typeCount("add_to_cart", 30)));
        Product brandProduct = mock(Product.class);
        when(brandProduct.getId()).thenReturn(37L);
        when(productRepository.findAllByBrandId(BRAND_ID)).thenReturn(List.of(brandProduct));
        List<BehaviorEvent> checkouts = List.of(
                checkoutEvent("{\"productIds\":[37,999]}"),
                checkoutEvent("{\"productIds\":[999]}"),
                checkoutEvent(null));
        when(behaviorEventRepository
                .findAllByEventTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                        anyString(), any(), any()))
                .thenReturn(checkouts);
        when(orderItemRepository.countSellerPurchaseOrders(eq(BRAND_ID), any(), any())).thenReturn(1L);

        SellerFunnelResponse response = service.funnel(BRAND_ID, null, null);

        assertThat(response.stages()).extracting(SellerFunnelResponse.Stage::count)
                .containsExactly(100L, 30L, 1L, 1L);
        assertThat(response.stages().get(1).conversionRate()).isEqualTo(30.0);
        assertThat(response.stages().get(0).conversionRate()).isNull();
    }

    @Test
    @DisplayName("I-8 IP 마스킹 — IPv4 마지막 옥텟, IPv6 프리픽스 외 마스킹, null은 unknown")
    void maskIpVariants() {
        assertThat(SellerAnalyticsService.maskIp("203.0.113.10")).isEqualTo("203.0.113.xxx");
        assertThat(SellerAnalyticsService.maskIp("2001:db8:1:2::5")).isEqualTo("2001:db8::xxxx");
        assertThat(SellerAnalyticsService.maskIp(null)).isEqualTo("unknown");
    }

    @Test
    @DisplayName("I-16 — 코호트는 로그인 이력 보유 구매 고객, 이탈은 inactiveDays 초과 (02 D32 단일 출처)")
    void churnCohortAndRate() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        when(orderItemRepository.findSellerBuyers(BRAND_ID)).thenReturn(List.of(
                buyer(1, 2, 200000), buyer(2, 1, 90000), buyer(3, 1, 50000)));
        // 1=활성(2일 전), 2=이탈(40일 전), 3=로그인 이력 없음 → 코호트 제외
        when(accountEventLogRepository.findLastLogins(any())).thenReturn(List.of(
                lastLogin(1, LocalDateTime.now().minusDays(2)),
                lastLogin(2, LocalDateTime.now().minusDays(40))));
        when(behaviorEventRepository.countPreChurnSignals(any())).thenReturn(List.of());

        SellerChurnResponse response = service.churn(BRAND_ID, 30);

        assertThat(response.customerCount()).isEqualTo(2);
        assertThat(response.churnedCount()).isEqualTo(1);
        assertThat(response.churnRate()).isEqualTo(50.0);
        assertThat(response.churned()).hasSize(1);
        assertThat(response.churned().get(0).memberId()).isEqualTo(2L);
    }
}
