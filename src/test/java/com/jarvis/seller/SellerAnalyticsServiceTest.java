package com.jarvis.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com.jarvis.seller.dto.BrandAccountEventAggregateResponse;
import com.jarvis.seller.dto.SellerChurnResponse;
import com.jarvis.seller.dto.SellerCustomerFeaturesResponse;
import com.jarvis.seller.dto.SellerEventsResponse;
import com.jarvis.seller.dto.SellerFunnelResponse;
import com.jarvis.seller.dto.SellerOrderEventsResponse;
import com.jarvis.seller.dto.SellerProductChangesResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * I-7 퍼널 + I-8 IP 집계·마스킹 + I-13 검증 + I-14 어뷰징 판정 + I-15 변경 이력 + I-16 이탈
 * + I-38 고객 피처 (04 §10, 노션 명세)
 */
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
    // 목이 아니라 진짜 라벨러 — 라벨의 결정성·브랜드별 상이를 테스트가 직접 확인해야 한다
    private final CustomerLabeler customerLabeler = new CustomerLabeler("test-label-secret");

    @BeforeEach
    void setUp() {
        service = new SellerAnalyticsService(behaviorEventRepository, orderItemRepository,
                orderStatusLogRepository, productChangeLogRepository, accountEventLogRepository,
                productRepository, brandRepository, customerLabeler, new ObjectMapper());
    }

    private static BehaviorEventRepository.TypeCountRow typeCount(String type, long cnt) {
        return new BehaviorEventRepository.TypeCountRow() {
            public String getEventType() { return type; }
            public Long getCnt() { return cnt; }
        };
    }

    /** 브랜드 필터는 SQL(JSON_OVERLAPS)이 하고, 매칭 상품 id 산출만 서비스가 파싱한다 */
    private static BehaviorEventRepository.CheckoutRow checkoutEvent(String properties) {
        return new BehaviorEventRepository.CheckoutRow() {
            public java.time.LocalDateTime getCreatedAt() { return java.time.LocalDateTime.now(); }
            public String getProperties() { return properties; }
        };
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
        List<BehaviorEventRepository.CheckoutRow> checkouts = List.of(
                checkoutEvent("{\"productIds\":[37,999]}"),
                checkoutEvent("{\"productIds\":[999]}"),
                checkoutEvent(null));
        when(behaviorEventRepository.findBrandCheckouts(anyString(), any(), any()))
                .thenReturn(checkouts);
        when(orderItemRepository.countSellerPurchaseOrders(eq(BRAND_ID), any(), any(), any()))
                .thenReturn(1L);

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

    private static BehaviorEventRepository.ProductTypeCountRow productTypeCount(long productId,
                                                                                String type,
                                                                                long cnt) {
        return new BehaviorEventRepository.ProductTypeCountRow() {
            public Long getProductId() { return productId; }
            public String getEventType() { return type; }
            public Long getCnt() { return cnt; }
        };
    }

    private static OrderItemRepository.ProductCountRow purchaseCount(long productId, long cnt) {
        return new OrderItemRepository.ProductCountRow() {
            public Long getProductId() { return productId; }
            public Long getCnt() { return cnt; }
        };
    }

    private static OrderItemRepository.DayCountRow purchaseDayCount(String day, long cnt) {
        return new OrderItemRepository.DayCountRow() {
            public String getDay() { return day; }
            public Long getCnt() { return cnt; }
        };
    }

    /** 브랜드 상품 id만 필요한 경로(checkout_start JSON 귀속 대상 집합) */
    private void stubBrandProductIds(Long... productIds) {
        stubBrandProducts(false, productIds);
    }

    /** groupBy=product는 productName까지 쓴다 */
    private void stubBrandProducts(Long... productIds) {
        stubBrandProducts(true, productIds);
    }

    private void stubBrandProducts(boolean withName, Long... productIds) {
        List<Product> products = new java.util.ArrayList<>();
        for (Long id : productIds) {
            Product product = mock(Product.class);
            when(product.getId()).thenReturn(id);
            if (withName) {
                when(product.getName()).thenReturn("상품" + id);
            }
            products.add(product);
        }
        when(productRepository.findAllByBrandId(BRAND_ID)).thenReturn(products);
    }

    @Test
    @DisplayName("I-13 groupBy=product — purchaseComplete는 주문 집계로 채운다, 구매만 있는 상품도 rows 등장 (#62)")
    void eventsByProductFillsPurchaseFromOrders() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        stubBrandProducts(37L, 41L);
        // 이벤트는 37번만 — 41번은 구매만 있고 조회·담기 이벤트가 없는 상품
        when(behaviorEventRepository.countSellerEventsByProductType(eq(BRAND_ID), any(), any(), any(),
                any())).thenReturn(List.of(productTypeCount(37L, "product_view", 10),
                        productTypeCount(37L, "add_to_cart", 4)));
        when(behaviorEventRepository.countSellerVisitorsByProduct(eq(BRAND_ID), any(), any(), any(),
                any())).thenReturn(List.of());
        when(orderItemRepository.countSellerPurchaseOrdersByProduct(eq(BRAND_ID), any(), any(), any()))
                .thenReturn(List.of(purchaseCount(37L, 3), purchaseCount(41L, 2)));

        SellerEventsResponse response = service.events(BRAND_ID, null, null, "product", PERIOD);

        assertThat(response.rows()).extracting(SellerEventsResponse.ProductRow::productId)
                .containsExactly(37L, 41L); // 활동량 내림차순 — 37번 17건 > 41번 2건
        assertThat(response.rows().get(0).counts()).containsEntry("purchaseComplete", 3L);
        assertThat(response.rows().get(1).counts()).containsEntry("purchaseComplete", 2L);
        assertThat(response.total()).isEqualTo(2);
    }

    @Test
    // ---- I-13 2026-08-06 신설분 (salesQuantity · 체류시간 4필드 · eventType 5종) ----

    private static BehaviorEventRepository.DwellSampleRow dwell(long productId, long seconds) {
        BehaviorEventRepository.DwellSampleRow row =
                mock(BehaviorEventRepository.DwellSampleRow.class);
        when(row.getProductId()).thenReturn(productId);
        when(row.getDwellSeconds()).thenReturn(seconds);
        return row;
    }

    private static OrderItemRepository.ProductQuantityRow quantity(long productId, long qty) {
        OrderItemRepository.ProductQuantityRow row =
                mock(OrderItemRepository.ProductQuantityRow.class);
        when(row.getProductId()).thenReturn(productId);
        when(row.getQuantity()).thenReturn(qty);
        return row;
    }

    private void stubProductBranchBasics() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        stubBrandProducts(37L);
        when(behaviorEventRepository.countSellerEventsByProductType(eq(BRAND_ID), any(), any(), any(),
                any())).thenReturn(List.of(productTypeCount(37L, "product_view", 10)));
        when(behaviorEventRepository.countSellerVisitorsByProduct(eq(BRAND_ID), any(), any(), any(),
                any())).thenReturn(List.of());
        lenient().when(orderItemRepository.countSellerPurchaseOrdersByProduct(eq(BRAND_ID), any(),
                any(), any())).thenReturn(List.of());
    }

    // 61건 / 74개는 "주문당 평균 1.2개"다 — 단위가 달라 직접 비교하면 안 된다
    @Test
    @DisplayName("I-13 — salesQuantity는 판매 수량이고 purchaseComplete(건수)와 단위가 다르다")
    void fillsSalesQuantity() {
        stubProductBranchBasics();
        OrderItemRepository.ProductQuantityRow qty = quantity(37L, 74L);
        when(orderItemRepository.sumSellerSalesByProduct(eq(BRAND_ID), any(), any()))
                .thenReturn(List.of(qty));
        when(behaviorEventRepository.findDwellSamples(eq(BRAND_ID), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        SellerEventsResponse response = service.events(BRAND_ID, null, null, "product", PERIOD);

        assertThat(response.rows().get(0).salesQuantity()).isEqualTo(74L);
    }

    // 0은 "안 팔림", null은 "미조회" — 구분이 없으면 LLM이 "판매 0"으로 오보한다
    @Test
    @DisplayName("I-13 — eventType에 purchase_complete가 없으면 salesQuantity는 null (0이 아니다)")
    void salesQuantityIsNullWhenNotRequested() {
        stubProductBranchBasics();
        when(behaviorEventRepository.findDwellSamples(eq(BRAND_ID), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        SellerEventsResponse response =
                service.events(BRAND_ID, "product_view,add_to_cart", null, "product", PERIOD);

        assertThat(response.rows().get(0).salesQuantity()).isNull();
        verify(orderItemRepository, never()).sumSellerSalesByProduct(any(), any(), any());
    }

    // 체류시간은 롱테일이라 평균은 방치 탭에 흔들린다 — 중앙값이 주 지표다
    @Test
    @DisplayName("I-13 — 체류시간은 중앙값·평균·표본수·산출방식 4종으로 내려간다")
    void fillsDwellStats() {
        stubProductBranchBasics();
        when(orderItemRepository.sumSellerSalesByProduct(eq(BRAND_ID), any(), any()))
                .thenReturn(List.of());
        // 10·20·30·900 → 중앙값 25, 평균 240
        List<BehaviorEventRepository.DwellSampleRow> samples = List.of(
                dwell(37L, 10), dwell(37L, 20), dwell(37L, 30), dwell(37L, 900));
        when(behaviorEventRepository.findDwellSamples(eq(BRAND_ID), any(), any(), any(), anyInt()))
                .thenReturn(samples);

        SellerEventsResponse.ProductRow row =
                service.events(BRAND_ID, null, null, "product", PERIOD).rows().get(0);

        assertThat(row.medianDwellSeconds()).isEqualTo(25.0);
        assertThat(row.avgDwellSeconds()).isEqualTo(240.0);
        assertThat(row.dwellSampleCount()).isEqualTo(4L);
        assertThat(row.dwellSource()).isEqualTo("next_event");
    }

    @Test
    @DisplayName("I-13 — 체류시간 표본이 없으면 4필드 모두 null (0이 아니다)")
    void dwellIsNullWithoutSamples() {
        stubProductBranchBasics();
        when(orderItemRepository.sumSellerSalesByProduct(eq(BRAND_ID), any(), any()))
                .thenReturn(List.of());
        when(behaviorEventRepository.findDwellSamples(eq(BRAND_ID), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        SellerEventsResponse.ProductRow row =
                service.events(BRAND_ID, null, null, "product", PERIOD).rows().get(0);

        assertThat(row.medianDwellSeconds()).isNull();
        assertThat(row.avgDwellSeconds()).isNull();
        assertThat(row.dwellSampleCount()).isNull();
        assertThat(row.dwellSource()).isNull();
    }

    // 이상치 상한은 BE 고정이다 — 호출마다 달라지면 같은 질문에 다른 숫자가 나온다
    @Test
    @DisplayName("I-13 — 체류시간 이상치 상한 1800초를 쿼리에 고정해 넘긴다")
    void dwellCapIsFixed() {
        stubProductBranchBasics();
        when(orderItemRepository.sumSellerSalesByProduct(eq(BRAND_ID), any(), any()))
                .thenReturn(List.of());
        when(behaviorEventRepository.findDwellSamples(eq(BRAND_ID), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        service.events(BRAND_ID, null, null, "product", PERIOD);

        verify(behaviorEventRepository).findDwellSamples(eq(BRAND_ID), any(), any(), any(), eq(1800));
    }

    // product_view가 조회 대상이 아니면 체류시간을 계산할 근거 자체가 없다
    @Test
    @DisplayName("I-13 — eventType에 product_view가 없으면 체류시간 쿼리를 아예 안 돈다")
    void skipsDwellWithoutProductView() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        stubBrandProducts(37L);
        when(behaviorEventRepository.countSellerEventsByProductType(eq(BRAND_ID), any(), any(), any(),
                any())).thenReturn(List.of(productTypeCount(37L, "add_to_cart", 4)));
        when(behaviorEventRepository.countSellerVisitorsByProduct(eq(BRAND_ID), any(), any(), any(),
                any())).thenReturn(List.of());

        service.events(BRAND_ID, "add_to_cart", null, "product", PERIOD);

        verify(behaviorEventRepository, never())
                .findDwellSamples(any(), any(), any(), any(), anyInt());
    }

    // 담김도 삭제도 활동이다 — 삭제가 잦은 상품의 순위가 올라가는 건 의도된 변경(노션 I-13)
    @Test
    @DisplayName("I-13 — eventType 5종에 remove_from_cart가 편입됐다")
    void acceptsRemoveFromCart() {
        stubProductBranchBasics();
        // purchase_complete를 안 물었으므로 판매 수량 쿼리는 돌지 않는다
        when(behaviorEventRepository.findDwellSamples(eq(BRAND_ID), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        SellerEventsResponse response =
                service.events(BRAND_ID, "product_view,remove_from_cart", null, "product", PERIOD);

        assertThat(response.rows().get(0).counts()).containsKey("removeFromCart");
    }

    @DisplayName("I-13 groupBy=eventType — purchaseComplete가 I-7 purchase 단과 같은 쿼리·같은 값 (#62)")
    void eventsByTypePurchaseMatchesFunnel() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        when(behaviorEventRepository.countSellerEventsByType(eq(BRAND_ID), any(), any(), any(), any()))
                .thenReturn(List.of(typeCount("product_view", 100), typeCount("add_to_cart", 30)));
        stubBrandProductIds(37L);
        when(behaviorEventRepository.findBrandCheckouts(anyString(), any(), any()))
                .thenReturn(List.of(checkoutEvent("{\"productIds\":[37]}")));
        when(orderItemRepository.countSellerPurchaseOrders(eq(BRAND_ID), any(), any(), any()))
                .thenReturn(12L);

        SellerEventsResponse events = service.events(BRAND_ID, null, null, "eventType", PERIOD);
        SellerFunnelResponse funnel = service.funnel(BRAND_ID, PERIOD);

        assertThat(events.counts()).containsEntry("purchaseComplete", 12L);
        assertThat(events.counts().get("purchaseComplete"))
                .isEqualTo(funnel.stages().get(3).count()); // I-7↔I-13 정합(수용 기준 2)
    }

    @Test
    @DisplayName("I-13 eventType=purchase_complete 단독 — 이벤트가 없어도 rows가 비지 않는다 (#62)")
    void eventsPurchaseOnlyStillReturnsRows() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        stubBrandProducts(37L);
        when(orderItemRepository.countSellerPurchaseOrdersByProduct(eq(BRAND_ID), any(), any(), any()))
                .thenReturn(List.of(purchaseCount(37L, 5)));

        SellerEventsResponse response =
                service.events(BRAND_ID, "purchase_complete", null, "product", PERIOD);

        assertThat(response.rows()).hasSize(1);
        assertThat(response.rows().get(0).counts()).containsExactly(
                java.util.Map.entry("purchaseComplete", 5L));
        // 조회 이벤트를 요청하지 않았으므로 전환율은 계산 불가
        assertThat(response.rows().get(0).viewToCartRate()).isNull();
    }

    @Test
    @DisplayName("I-13 groupBy=date — 구매는 paid_at 일자에 얹히고 빈 일자는 0 (#62)")
    void eventsByDateFillsPurchaseFromOrders() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        when(orderItemRepository.countSellerPurchaseOrdersByDate(eq(BRAND_ID), any(), any(), any()))
                .thenReturn(List.of(purchaseDayCount("2026-06-02", 4)));

        SellerEventsResponse response =
                service.events(BRAND_ID, "purchase_complete", null, "date", PERIOD);

        assertThat(response.series()).hasSize(30);
        assertThat(response.series().get(1)).containsEntry("purchaseComplete", 4L);
        assertThat(response.series().get(0)).containsEntry("purchaseComplete", 0L);
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

    // 2026-08-11 정합 수정 — 종전엔 어휘 밖 groupBy를 조용히 기본 rows 모드로 무시해 명세(400)와 어긋났다
    @Test
    @DisplayName("I-14 — groupBy가 memberId 밖의 값이면 INVALID_GROUP_BY (조회 전에 걸린다)")
    void orderEventsRejectsUnknownGroupBy() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.orderEvents(BRAND_ID, null, null, PERIOD, false, "date", 100))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_GROUP_BY);
        verifyNoInteractions(orderStatusLogRepository);
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
        // memberId가 아니라 라벨이 나간다 (노션 I-14 2026-08-06 프라이버시 개정)
        assertThat(rows.get(0).customerLabel()).isEqualTo(customerLabeler.label(BRAND_ID, 1L));
        // 같은 회원이라도 브랜드가 다르면 라벨이 달라야 한다 — 브랜드 간 대조 추적 차단
        assertThat(customerLabeler.label(BRAND_ID, 1L))
                .isNotEqualTo(customerLabeler.label(BRAND_ID + 1, 1L));
        assertThat(rows.get(0).cancelRatio()).isEqualTo(0.75);
        assertThat(rows.get(0).isSuspicious()).isTrue(); // 취소율 초과
        assertThat(rows.get(1).isSuspicious()).isTrue(); // 시간당 주문 초과
        assertThat(rows.get(1).maxOrdersPerHour()).isEqualTo(12L);
        assertThat(rows.get(2).isSuspicious()).isFalse();
    }

    private static ProductChangeLogRepository.ChangeRow changeRow(long productId, String name,
                                                                  String type, String oldValue,
                                                                  String newValue,
                                                                  LocalDateTime createdAt) {
        return new ProductChangeLogRepository.ChangeRow() {
            public Long getProductId() { return productId; }
            public String getProductName() { return name; }
            public String getChangeType() { return type; }
            public String getOldValue() { return oldValue; }
            public String getNewValue() { return newValue; }
            public LocalDateTime getCreatedAt() { return createdAt; }
        };
    }

    @Test
    @DisplayName("I-15 — changeType·productId 필터, [당일 00:00, 익일 00:00) 기간, limit 상한 500, rows 매핑·KST·total (노션 I-15)")
    void productChangesFiltersMappingAndLimitClamp() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        LocalDateTime fromDt = LocalDateTime.of(2026, 6, 1, 0, 0);
        LocalDateTime toDt = LocalDateTime.of(2026, 7, 1, 0, 0);
        when(productChangeLogRepository.findSellerProductChanges(
                eq(BRAND_ID), eq("STOCK"), eq(37L), eq(fromDt), eq(toDt), eq(500)))
                .thenReturn(List.of(changeRow(37L, "무선 이어폰", "STOCK", "5", "0",
                        LocalDateTime.of(2026, 6, 15, 12, 0))));
        when(productChangeLogRepository.countSellerProductChanges(
                eq(BRAND_ID), eq("STOCK"), eq(37L), eq(fromDt), eq(toDt)))
                .thenReturn(42L);

        SellerProductChangesResponse response =
                service.productChanges(BRAND_ID, "STOCK", 37L, PERIOD, 1000);

        assertThat(response.brandId()).isEqualTo(BRAND_ID);
        assertThat(response.from()).isEqualTo(PERIOD.from());
        assertThat(response.to()).isEqualTo(PERIOD.to());
        assertThat(response.total()).isEqualTo(42L); // LIMIT 미적용 전체 건수
        assertThat(response.rows()).hasSize(1);
        SellerProductChangesResponse.Row row = response.rows().get(0);
        assertThat(row.productId()).isEqualTo(37L);
        assertThat(row.productName()).isEqualTo("무선 이어폰");
        assertThat(row.changeType()).isEqualTo("STOCK");
        assertThat(row.oldValue()).isEqualTo("5");
        assertThat(row.newValue()).isEqualTo("0"); // 품절 신호 = STOCK newValue "0" (04 §10)
        assertThat(row.createdAt().getOffset().getId()).isEqualTo("+09:00");
    }

    @Test
    @DisplayName("I-15 — changeType이 PRICE·STOCK·STATUS 외 값이면 VALIDATION_ERROR (노션 I-15)")
    void productChangesRejectsUnknownChangeType() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.productChanges(BRAND_ID, "bogus", null, PERIOD, 100))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("I-15 — 기간 내 변경 이력이 없으면 rows 빈 배열·total 0")
    void productChangesEmptyResult() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        when(productChangeLogRepository.findSellerProductChanges(
                eq(BRAND_ID), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(productChangeLogRepository.countSellerProductChanges(
                eq(BRAND_ID), any(), any(), any(), any()))
                .thenReturn(0L);

        SellerProductChangesResponse response =
                service.productChanges(BRAND_ID, null, null, PERIOD, 100);

        assertThat(response.rows()).isEmpty();
        assertThat(response.total()).isZero();
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
        assertThat(member.lastActivityAt()).isNotNull();
        // memberId가 아니라 라벨 · lastLoginAt은 제거됐다 (노션 I-16 2026-08-06)
        assertThat(member.customerLabel()).isEqualTo(customerLabeler.label(BRAND_ID, 2L));
        assertThat(member.sessions30d()).isZero();
        assertThat(member.preChurnEvent()).isEqualTo("RETURNED(상품불량)");
    }

    // "이탈 0%"가 아니라 "계산할 모수가 없다" — 0.0으로 내려보내면 LLM이 그걸 0%로 보고한다
    @Test
    @DisplayName("I-16 — 코호트가 비면 churnRate는 0.0이 아니라 null (노션 I-16)")
    void churnRateIsNullWhenCohortEmpty() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        when(behaviorEventRepository.findChurnCohortMemberIds(eq(BRAND_ID), any(), any()))
                .thenReturn(List.of());

        SellerChurnResponse response = service.churn(BRAND_ID, PERIOD, 30);

        assertThat(response.churnRate()).isNull();
        assertThat(response.cohortSize()).isZero();
        assertThat(response.members()).isEmpty();
    }

    // ---- I-8 자사 코호트 (노션 I-8 2026-08-06 전역 → 브랜드 스코프 전환) ----

    @Test
    @DisplayName("I-8 브랜드 스코프 — 코호트가 비면 200 + 빈 rows (이상 없음은 정상 결과)")
    void brandAccountEventsWithEmptyCohort() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        when(behaviorEventRepository.findChurnCohortMemberIds(eq(BRAND_ID), any(), any()))
                .thenReturn(List.of());

        BrandAccountEventAggregateResponse response =
                service.brandAccountEvents(BRAND_ID, "ip", null, PERIOD);

        assertThat(response.rows()).isEmpty();
        assertThat(response.scope()).isEqualTo("brand");
        // 코호트가 없으면 로그 집계 자체를 하지 않는다
        verifyNoInteractions(accountEventLogRepository);
    }

    @Test
    @DisplayName("I-8 브랜드 스코프 — groupBy=ip는 IP를 마스킹하고 어뷰징 회원 수를 함께 센다")
    void brandAccountEventsGroupByIp() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        when(behaviorEventRepository.findChurnCohortMemberIds(eq(BRAND_ID), any(), any()))
                .thenReturn(List.of(1L, 2L));
        when(orderStatusLogRepository.maxSellerOrdersPerHourByMember(
                eq(BRAND_ID), eq(false), any(), any(), any(), any())).thenReturn(List.of());
        when(orderStatusLogRepository.aggregateSellerOrderEventsByMember(
                eq(BRAND_ID), eq(false), any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        AccountEventLogRepository.CohortIpAggRow ipRow = cohortIpRow("211.234.10.20", 7L, 5L, 87L);
        when(accountEventLogRepository.aggregateByIpForCohort(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(ipRow));

        BrandAccountEventAggregateResponse response =
                service.brandAccountEvents(BRAND_ID, "ip", null, PERIOD);

        BrandAccountEventAggregateResponse.IpRow row =
                (BrandAccountEventAggregateResponse.IpRow) response.rows().get(0);
        assertThat(row.ipMasked()).isEqualTo("211.234.xx.xx");
        assertThat(row.distinctMembers()).isEqualTo(7L);
        assertThat(row.suspiciousMemberCount()).isEqualTo(5L);
        assertThat(row.eventCount()).isEqualTo(87L);
    }

    @Test
    @DisplayName("I-8 브랜드 스코프 — groupBy가 어휘 밖이면 INVALID_GROUP_BY (코호트 조회 전에 걸린다)")
    void brandAccountEventsRejectsUnknownGroupBy() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.brandAccountEvents(BRAND_ID, "memberId", null, PERIOD))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_GROUP_BY);
        verifyNoInteractions(behaviorEventRepository);
    }

    @Test
    @DisplayName("I-8 브랜드 스코프 — 없는 브랜드는 404 BRAND_NOT_FOUND (빈 코호트 200과 구분)")
    void brandAccountEventsRejectsUnknownBrand() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.brandAccountEvents(BRAND_ID, "ip", null, PERIOD))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BRAND_NOT_FOUND);
    }

    private static AccountEventLogRepository.CohortIpAggRow cohortIpRow(
            String ip, long distinctMembers, long suspiciousMembers, long eventCount) {
        AccountEventLogRepository.CohortIpAggRow row =
                mock(AccountEventLogRepository.CohortIpAggRow.class);
        when(row.getIp()).thenReturn(ip);
        when(row.getDistinctMembers()).thenReturn(distinctMembers);
        when(row.getSuspiciousMembers()).thenReturn(suspiciousMembers);
        when(row.getEventCount()).thenReturn(eventCount);
        when(row.getFirstSeen()).thenReturn(java.time.LocalDateTime.of(2026, 6, 1, 0, 0));
        when(row.getLastSeen()).thenReturn(java.time.LocalDateTime.of(2026, 6, 30, 0, 0));
        return row;
    }

    // ---- I-38 고객 행동 피처 (노션 I-38 2026-08-10 확정) ----

    private static BehaviorEventRepository.MemberTypeCountRow memberTypeCount(long memberId,
                                                                              String type,
                                                                              long cnt) {
        return new BehaviorEventRepository.MemberTypeCountRow() {
            public Long getMemberId() { return memberId; }
            public String getEventType() { return type; }
            public Long getCnt() { return cnt; }
        };
    }

    private static BehaviorEventRepository.MemberCheckoutRow memberCheckout(long memberId,
                                                                            String properties) {
        return new BehaviorEventRepository.MemberCheckoutRow() {
            public Long getMemberId() { return memberId; }
            public String getProperties() { return properties; }
        };
    }

    private static BehaviorEventRepository.MemberCntRow memberCnt(long memberId, long cnt) {
        return new BehaviorEventRepository.MemberCntRow() {
            public Long getMemberId() { return memberId; }
            public Long getCnt() { return cnt; }
        };
    }

    private static BehaviorEventRepository.ActivitySpanRow activitySpan(long memberId,
                                                                        LocalDateTime firstSeen,
                                                                        LocalDateTime lastActivity) {
        return new BehaviorEventRepository.ActivitySpanRow() {
            public Long getMemberId() { return memberId; }
            public LocalDateTime getFirstSeen() { return firstSeen; }
            public LocalDateTime getLastActivity() { return lastActivity; }
        };
    }

    private static OrderItemRepository.CustomerOrderRow customerOrder(long memberId,
                                                                      long orderCount,
                                                                      long amount) {
        return new OrderItemRepository.CustomerOrderRow() {
            public Long getMemberId() { return memberId; }
            public Long getOrderCount() { return orderCount; }
            public Long getAmount() { return amount; }
        };
    }

    private static OrderStatusLogRepository.MemberCancelRow memberCancel(long memberId, long cnt) {
        return new OrderStatusLogRepository.MemberCancelRow() {
            public Long getMemberId() { return memberId; }
            public Long getCnt() { return cnt; }
        };
    }

    private static List<Long> cohortOf(int size) {
        return java.util.stream.LongStream.rangeClosed(1, size).boxed().toList();
    }

    @Test
    @DisplayName("I-38 — 라벨·이벤트 3종·구간 금액·일 단위 절사로 회원별 피처를 조립한다 (노션 I-38)")
    void customerFeaturesAggregatesPerCustomer() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        when(behaviorEventRepository.findChurnCohortMemberIds(eq(BRAND_ID), any(), any()))
                .thenReturn(cohortOf(30));
        when(behaviorEventRepository.countCustomerEventsByType(eq(BRAND_ID), any(), any(), any()))
                .thenReturn(List.of(memberTypeCount(1L, "product_view", 35L),
                        memberTypeCount(1L, "add_to_cart", 8L)));
        stubBrandProducts(false, 100L);
        // 마지막 행(productIds에 자사 상품 없음)은 서비스가 파싱해 걸러야 한다 — I-7·I-13과 같은 판정
        when(behaviorEventRepository.findCustomerCheckouts(anyString(), any(), any()))
                .thenReturn(List.of(memberCheckout(1L, "{\"productIds\":[100]}"),
                        memberCheckout(1L, "{\"productIds\":[100,999]}"),
                        memberCheckout(1L, "{\"productIds\":[100]}"),
                        memberCheckout(1L, "{\"productIds\":[999]}")));
        when(behaviorEventRepository.countCustomerSessions(any(), any(), any()))
                .thenReturn(List.of(memberCnt(1L, 12L)));
        when(behaviorEventRepository.findCustomerActivitySpans(eq(BRAND_ID), any(), any()))
                .thenReturn(List.of(activitySpan(1L, LocalDateTime.of(2026, 4, 1, 9, 0),
                        LocalDateTime.of(2026, 6, 16, 9, 0))));
        when(orderItemRepository.sumSellerOrdersByCustomer(eq(BRAND_ID), any(), any(), any()))
                .thenReturn(List.of(customerOrder(1L, 2L, 75_000L)));
        when(orderStatusLogRepository.countCancelsByCustomer(eq(BRAND_ID), any(), any(), any()))
                .thenReturn(List.of(memberCancel(1L, 1L)));

        SellerCustomerFeaturesResponse response = service.customerFeatures(BRAND_ID, PERIOD);

        assertThat(response.totalCustomers()).isEqualTo(30);
        assertThat(response.insufficientCohort()).isFalse();
        assertThat(response.truncated()).isFalse();
        assertThat(response.rowLimit()).isEqualTo(1000);
        assertThat(response.amountBuckets())
                .isEqualTo(SellerCustomerFeaturesResponse.AMOUNT_BUCKETS);
        assertThat(response.rows()).hasSize(30);

        // 활동량 내림차순이라 유일하게 이벤트가 있는 1번이 머리에 온다
        SellerCustomerFeaturesResponse.Row top = response.rows().get(0);
        assertThat(top.customerLabel()).isEqualTo(customerLabeler.label(BRAND_ID, 1L));
        assertThat(top.sessions()).isEqualTo(12);
        assertThat(top.productViews()).isEqualTo(35);
        assertThat(top.cartAdds()).isEqualTo(8);
        assertThat(top.checkoutStarts()).isEqualTo(3);
        assertThat(top.orderCount()).isEqualTo(2);
        assertThat(top.cancelCount()).isEqualTo(1);
        assertThat(top.amountBucket()).isEqualTo("50K_100K");
        assertThat(top.lastActivityDaysAgo()).isEqualTo(14);
        assertThat(top.firstSeenDaysAgo()).isEqualTo(90);

        // 활동 없는 회원도 행으로 남되 전부 0 + ZERO 구간이다
        SellerCustomerFeaturesResponse.Row tail = response.rows().get(29);
        assertThat(tail.productViews()).isZero();
        assertThat(tail.orderCount()).isZero();
        assertThat(tail.amountBucket()).isEqualTo("ZERO");
        // memberId·원금액은 어떤 필드로도 나가지 않는다 — 라벨은 6자 사례번호다
        assertThat(top.customerLabel()).hasSize(6).isNotEqualTo("1");
    }

    // "고객이 없다"가 아니라 "표본이 모자라다" — 소집단 재식별 차단이자 과소 표본 세그멘테이션 방지
    @Test
    @DisplayName("I-38 — 모집단 30명 미만이면 rows 없이 insufficientCohort (노션 I-38)")
    void customerFeaturesGuardsSmallCohort() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        when(behaviorEventRepository.findChurnCohortMemberIds(eq(BRAND_ID), any(), any()))
                .thenReturn(cohortOf(29));

        SellerCustomerFeaturesResponse response = service.customerFeatures(BRAND_ID, PERIOD);

        assertThat(response.insufficientCohort()).isTrue();
        assertThat(response.rows()).isEmpty();
        assertThat(response.totalCustomers()).isEqualTo(29);
        assertThat(response.truncated()).isFalse();
    }

    @Test
    @DisplayName("I-38 — rowLimit 초과분은 잘리고 truncated=true, totalCustomers는 자르기 전 수 (노션 I-38)")
    void customerFeaturesTruncatesBeyondRowLimit() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        when(behaviorEventRepository.findChurnCohortMemberIds(eq(BRAND_ID), any(), any()))
                .thenReturn(cohortOf(1001));
        when(behaviorEventRepository.countCustomerEventsByType(eq(BRAND_ID), any(), any(), any()))
                .thenReturn(List.of());
        when(productRepository.findAllByBrandId(BRAND_ID)).thenReturn(List.of());
        when(behaviorEventRepository.countCustomerSessions(any(), any(), any()))
                .thenReturn(List.of());
        when(behaviorEventRepository.findCustomerActivitySpans(eq(BRAND_ID), any(), any()))
                .thenReturn(List.of());
        when(orderItemRepository.sumSellerOrdersByCustomer(eq(BRAND_ID), any(), any(), any()))
                .thenReturn(List.of());
        when(orderStatusLogRepository.countCancelsByCustomer(eq(BRAND_ID), any(), any(), any()))
                .thenReturn(List.of());

        SellerCustomerFeaturesResponse response = service.customerFeatures(BRAND_ID, PERIOD);

        assertThat(response.rows()).hasSize(1000);
        assertThat(response.truncated()).isTrue();
        assertThat(response.totalCustomers()).isEqualTo(1001);
    }
}
