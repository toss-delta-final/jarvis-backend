package com.jarvis.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.brand.BrandRepository;
import com.jarvis.global.event.BehaviorEvent;
import com.jarvis.global.event.BehaviorEventRepository;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.member.AccountEventLogRepository;
import com.jarvis.order.OrderItemRepository;
import com.jarvis.order.OrderStatusLogRepository;
import com.jarvis.product.Product;
import com.jarvis.product.ProductChangeLogRepository;
import com.jarvis.product.ProductRepository;
import com.jarvis.seller.dto.AccountEventAggregateResponse;
import com.jarvis.seller.dto.SellerChurnResponse;
import com.jarvis.seller.dto.SellerFunnelResponse;
import com.jarvis.seller.dto.SellerOrderEventsResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** I-7 퍼널 + I-8 IP 집계·마스킹 + I-13 검증 + I-14 어뷰징 판정 + I-16 이탈 (04 §10, 노션 명세) */
@ExtendWith(MockitoExtension.class)
class SellerAnalyticsServiceTest {

    private static final Long BRAND_ID = 7L;
    private static final AnalysisPeriod PERIOD = AnalysisPeriod.of("2026-06-01", "2026-06-30");

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

    @Test
    @DisplayName("I-7 — 3단은 productIds 자사 포함분만, purchase_complete·source·computable·소수 전환율 (노션 I-7)")
    void funnelStagesAndConversionRates() {
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

        SellerFunnelResponse response = service.funnel(BRAND_ID, PERIOD);

        assertThat(response.stages()).extracting(SellerFunnelResponse.Stage::stage)
                .containsExactly("product_view", "add_to_cart", "checkout_start", "purchase_complete");
        assertThat(response.stages()).extracting(SellerFunnelResponse.Stage::count)
                .containsExactly(100L, 30L, 1L, 1L);
        assertThat(response.stages()).extracting(SellerFunnelResponse.Stage::source)
                .containsExactly("events", "events", "events", "orders");
        assertThat(response.stages()).extracting(SellerFunnelResponse.Stage::computable)
                .containsExactly(null, null, Boolean.TRUE, null);
        assertThat(response.conversionRates().viewToCart()).isEqualTo(0.3);
        assertThat(response.conversionRates().cartToCheckout()).isEqualTo(0.033);
        assertThat(response.conversionRates().checkoutToPurchase()).isEqualTo(1.0);
        assertThat(response.conversionRates().overall()).isEqualTo(0.01);
    }

    @Test
    @DisplayName("I-8 IP 마스킹 — IPv4 마지막 두 옥텟(노션 211.234.xx.xx), IPv6 프리픽스 외, null은 unknown")
    void maskIpVariants() {
        assertThat(SellerAnalyticsService.maskIp("203.0.113.10")).isEqualTo("203.0.xx.xx");
        assertThat(SellerAnalyticsService.maskIp("2001:db8:1:2::5")).isEqualTo("2001:db8::xxxx");
        assertThat(SellerAnalyticsService.maskIp(null)).isEqualTo("unknown");
    }

    private static AccountEventLogRepository.IpAggRow ipAgg(String ip, long fail, long members,
                                                            long nullCnt, long total) {
        return new AccountEventLogRepository.IpAggRow() {
            public String getIp() { return ip; }
            public Long getFailCount() { return fail; }
            public Long getDistinctMembers() { return members; }
            public Long getNullMemberCnt() { return nullCnt; }
            public Long getTotalCnt() { return total; }
            public LocalDateTime getFirstSeen() { return LocalDateTime.of(2026, 6, 1, 10, 0); }
            public LocalDateTime getLastSeen() { return LocalDateTime.of(2026, 6, 2, 10, 0); }
        };
    }

    @Test
    @DisplayName("I-8 groupBy=ip — 마스킹·nullMemberRatio·LOGIN_FAIL 버스트 판정 rows (노션 I-8)")
    void accountEventsIpRows() {
        when(accountEventLogRepository.aggregateByIp(eq("LOGIN_FAIL"), any(), any(), anyInt()))
                .thenReturn(List.of(ipAgg("211.234.56.78", 87, 23, 60, 100),
                        ipAgg("10.0.0.1", 3, 2, 0, 5)));

        AccountEventAggregateResponse response = service.accountEvents("ip", "LOGIN_FAIL", PERIOD);

        assertThat(response.groupBy()).isEqualTo("ip");
        AccountEventAggregateResponse.IpRow row =
                (AccountEventAggregateResponse.IpRow) response.rows().get(0);
        assertThat(row.ipMasked()).isEqualTo("211.234.xx.xx");
        assertThat(row.failCount()).isEqualTo(87);
        assertThat(row.distinctMembers()).isEqualTo(23);
        assertThat(row.nullMemberRatio()).isEqualTo(0.6);
        assertThat(row.isSuspicious()).isTrue();
        assertThat(row.firstSeen().getOffset().getId()).isEqualTo("+09:00");
        AccountEventAggregateResponse.IpRow quiet =
                (AccountEventAggregateResponse.IpRow) response.rows().get(1);
        assertThat(quiet.isSuspicious()).isFalse();
    }

    @Test
    @DisplayName("I-13 — groupBy·eventType 값 오류는 INVALID_GROUP_BY (노션 I-13)")
    void eventsRejectsInvalidGroupByAndEventType() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.events(BRAND_ID, null, null, "bogus", PERIOD))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_GROUP_BY);
        assertThatThrownBy(() -> service.events(BRAND_ID, "product_view,login", null, "product", PERIOD))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_GROUP_BY);
    }

    private static OrderStatusLogRepository.MemberAggRow memberAgg(long memberId, long orders,
                                                                   long cancels) {
        return new OrderStatusLogRepository.MemberAggRow() {
            public Long getMemberId() { return memberId; }
            public Long getOrderCount() { return orders; }
            public Long getCancelCount() { return cancels; }
        };
    }

    private static OrderStatusLogRepository.MemberHourRow memberHour(long memberId, long max) {
        return new OrderStatusLogRepository.MemberHourRow() {
            public Long getMemberId() { return memberId; }
            public Long getMaxPerHour() { return max; }
        };
    }

    @Test
    @DisplayName("I-14 groupBy=memberId — cancelRatio>0.5 또는 maxOrdersPerHour>10이면 suspicious (노션 I-14)")
    void orderEventsMemberGroupingFlagsSuspicious() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        when(orderStatusLogRepository.aggregateSellerOrderEventsByMember(
                eq(BRAND_ID), eq(false), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(memberAgg(1, 4, 3), memberAgg(2, 20, 2), memberAgg(3, 5, 1)));
        when(orderStatusLogRepository.maxSellerOrdersPerHourByMember(
                eq(BRAND_ID), eq(false), any(), any(), any(), any()))
                .thenReturn(List.of(memberHour(2, 12), memberHour(3, 3)));

        SellerOrderEventsResponse response = service.orderEvents(BRAND_ID, null, null, PERIOD,
                false, "memberId", 100);

        assertThat(response.total()).isEqualTo(3);
        List<SellerOrderEventsResponse.MemberRow> rows = response.rows().stream()
                .map(SellerOrderEventsResponse.MemberRow.class::cast).toList();
        assertThat(rows.get(0).buyerMemberId()).isEqualTo(1L);
        assertThat(rows.get(0).cancelRatio()).isEqualTo(0.75);
        assertThat(rows.get(0).isSuspicious()).isTrue(); // 취소율 초과
        assertThat(rows.get(1).isSuspicious()).isTrue(); // 시간당 주문 초과
        assertThat(rows.get(1).maxOrdersPerHour()).isEqualTo(12L);
        assertThat(rows.get(2).isSuspicious()).isFalse();
    }

    private static BehaviorEventRepository.LastActivityRow lastActivity(long memberId,
                                                                        LocalDateTime at) {
        return new BehaviorEventRepository.LastActivityRow() {
            public Long getMemberId() { return memberId; }
            public LocalDateTime getLastActivity() { return at; }
        };
    }

    @Test
    @DisplayName("I-16 — behavior_events 무활동 기준 코호트·소수 churnRate·preChurnSignals·members (노션 I-16)")
    void churnCohortSignalsAndMembers() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        when(behaviorEventRepository.findChurnCohortMemberIds(eq(BRAND_ID), any(), any()))
                .thenReturn(List.of(1L, 2L));
        // 1=활성(1일 전), 2=이탈(40일 전)
        when(behaviorEventRepository.findLastActivities(any())).thenReturn(List.of(
                lastActivity(1, LocalDateTime.now().minusDays(1)),
                lastActivity(2, LocalDateTime.now().minusDays(40))));
        when(orderStatusLogRepository.countChurnedMemberCancels(eq(BRAND_ID), any())).thenReturn(3L);
        when(orderStatusLogRepository.findChurnedMemberReturnReasons(eq(BRAND_ID), any(), anyInt()))
                .thenReturn(List.of(new OrderStatusLogRepository.ReasonCountRow() {
                    public String getReason() { return "상품불량"; }
                    public Long getCnt() { return 2L; }
                }));
        when(behaviorEventRepository.countPriceIncreaseExposedMembers(eq(BRAND_ID), any()))
                .thenReturn(1L);
        when(accountEventLogRepository.findLastLogins(any()))
                .thenReturn(List.of(new AccountEventLogRepository.LastLoginRow() {
                    public Long getMemberId() { return 2L; }
                    public LocalDateTime getLastLogin() { return LocalDateTime.now().minusDays(35); }
                }));
        when(behaviorEventRepository.countRecentSessions(any(), any())).thenReturn(List.of());
        when(orderStatusLogRepository.findChurnedMemberClaims(eq(BRAND_ID), any()))
                .thenReturn(List.of(new OrderStatusLogRepository.ClaimRow() {
                    public Long getMemberId() { return 2L; }
                    public String getToStatus() { return "RETURNED"; }
                    public String getReason() { return "상품불량"; }
                }));
        when(behaviorEventRepository.findLastEventTypes(any())).thenReturn(List.of());

        SellerChurnResponse response = service.churn(BRAND_ID, PERIOD, 30);

        assertThat(response.cohortSize()).isEqualTo(2);
        assertThat(response.churnRate()).isEqualTo(0.5);
        assertThat(response.inactiveDays()).isEqualTo(30);
        assertThat(response.preChurnSignals().cancelCount()).isEqualTo(3);
        assertThat(response.preChurnSignals().returnReasonsTop())
                .containsExactly(new SellerChurnResponse.ReasonCount("상품불량", 2));
        assertThat(response.preChurnSignals().zeroResultSearchSessions()).isZero();
        assertThat(response.preChurnSignals().priceIncreaseExposed()).isEqualTo(1);
        assertThat(response.members()).hasSize(1);
        SellerChurnResponse.Member member = response.members().get(0);
        assertThat(member.memberId()).isEqualTo(2L);
        assertThat(member.lastActivityAt()).isNotNull();
        assertThat(member.lastLoginAt()).isNotNull();
        assertThat(member.sessions30d()).isZero();
        assertThat(member.preChurnEvent()).isEqualTo("RETURNED(상품불량)");
    }
}
