package com.jarvis.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jarvis.brand.BrandRepository;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.order.Order;
import com.jarvis.order.OrderItem;
import com.jarvis.order.OrderItemRepository;
import com.jarvis.order.OrderItemStatus;
import com.jarvis.order.OrderRepository;
import com.jarvis.order.OrderStatusChanger;
import com.jarvis.product.Product;
import com.jarvis.product.ProductRepository;
import com.jarvis.seller.dto.SellerOrderInternalResponse;
import com.jarvis.seller.dto.SellerOrderListResponse;
import com.jarvis.seller.dto.SellerShipRequest;
import com.jarvis.seller.dto.SellerShipResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** S-2 (노션 S-2) — 주문 단위 대표상태·claimStatus·myItemsAmount 파생 + tabCounts */
@ExtendWith(MockitoExtension.class)
class SellerOrderServiceTest {

    private static final Long BRAND_ID = 7L;

    @Mock private OrderItemRepository orderItemRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private ProductRepository productRepository;
    @Mock private BrandRepository brandRepository;
    @Mock private OrderStatusChanger statusChanger;

    @InjectMocks private SellerOrderService service;

    private static OrderItemRepository.StatusCountRow tab(String bucket, long cnt) {
        return new OrderItemRepository.StatusCountRow() {
            public String getBucket() { return bucket; }
            public Long getCnt() { return cnt; }
        };
    }

    private static OrderItem item(long orderId, long productId, OrderItemStatus status, int price, int qty) {
        OrderItem i = mock(OrderItem.class);
        lenient().when(i.getId()).thenReturn(5000L + productId); // I-29 orderItemId 확인용
        lenient().when(i.getOrderId()).thenReturn(orderId);
        lenient().when(i.getProductId()).thenReturn(productId);
        lenient().when(i.getStatus()).thenReturn(status);
        lenient().when(i.getPrice()).thenReturn(price);
        lenient().when(i.getQuantity()).thenReturn(qty);
        lenient().when(i.getOptionName()).thenReturn("블루/M");
        lenient().when(i.getProductName()).thenReturn("스냅샷" + productId);
        return i;
    }

    private static Order order(long id) {
        Order o = mock(Order.class);
        lenient().when(o.getId()).thenReturn(id);
        lenient().when(o.orderNo()).thenReturn("ORD-20260716-" + id);
        lenient().when(o.getCreatedAt()).thenReturn(LocalDateTime.of(2026, 7, 16, 9, 42));
        lenient().when(o.getRecipient()).thenReturn("김서연");
        lenient().when(o.getPaymentMethod()).thenReturn("MOCK_CARD");
        return o;
    }

    private static Product product(long id) {
        Product p = mock(Product.class);
        lenient().when(p.getId()).thenReturn(id);
        lenient().when(p.getName()).thenReturn("상품" + id);
        lenient().when(p.getImageUrl()).thenReturn("/p" + id + ".jpg");
        return p;
    }

    @Test
    @DisplayName("대표상태·claimStatus·myItemsAmount 파생 + tabCounts (노션 S-2)")
    void listDerivesRepresentativeStatusAndTabCounts() {
        // mock은 지역변수로 먼저 생성 — thenReturn(...) 인자 안에서 mock/stub을 만들면 스터빙이 중첩된다
        Order o10 = order(10L);
        Order o11 = order(11L);
        // order10: ORDERED + SHIPPING → 대표 ORDERED, claim 없음
        // order11: DELIVERED + RETURN_REQUESTED → 대표 DELIVERED, claim RETURN_REQUESTED
        List<OrderItem> items = List.of(
                item(10L, 1L, OrderItemStatus.ORDERED, 30000, 1),
                item(10L, 2L, OrderItemStatus.SHIPPING, 50000, 1),
                item(11L, 3L, OrderItemStatus.DELIVERED, 40000, 2),
                item(11L, 4L, OrderItemStatus.RETURN_REQUESTED, 10000, 1));
        List<Product> products = List.of(product(1L), product(2L), product(3L), product(4L));

        when(orderItemRepository.countSellerOrderTabs(eq(BRAND_ID), any(), any(), any()))
                .thenReturn(List.of(tab("ORDERED", 2), tab("CLAIM", 1)));
        when(orderItemRepository.findSellerOrderIdsByTab(eq(BRAND_ID), any(), any(), any(), any(),
                anyInt(), anyLong())).thenReturn(List.of(10L, 11L));
        when(orderRepository.findAllById(any())).thenReturn(List.of(o10, o11));
        when(orderItemRepository.findSellerItemsByOrderIds(eq(BRAND_ID), any())).thenReturn(items);
        when(productRepository.findAllById(any())).thenReturn(products);

        SellerOrderListResponse res = service.list(BRAND_ID, null, 0, 20);

        assertThat(res.tabCounts()).containsEntry("ALL", 3L).containsEntry("ORDERED", 2L)
                .containsEntry("SHIPPING", 0L).containsEntry("DELIVERED", 0L).containsEntry("CLAIM", 1L);

        SellerOrderListResponse.Row row10 = res.content().get(0);
        assertThat(row10.orderId()).isEqualTo(10L);
        assertThat(row10.status()).isEqualTo("ORDERED");        // 가장 뒤진 단계
        assertThat(row10.claimStatus()).isNull();
        assertThat(row10.myItemCount()).isEqualTo(2);
        assertThat(row10.myItemsAmount()).isEqualTo(80000);      // 30000 + 50000
        assertThat(row10.representativeProduct().productId()).isEqualTo(2L); // 금액 최대(50000)

        SellerOrderListResponse.Row row11 = res.content().get(1);
        assertThat(row11.status()).isEqualTo("DELIVERED");
        assertThat(row11.claimStatus()).isEqualTo("RETURN_REQUESTED");
        assertThat(row11.myItemsAmount()).isEqualTo(90000);      // 40000*2 + 10000 (RETURN_REQUESTED은 완료 아님 → 포함)
        assertThat(row11.representativeProduct().productId()).isEqualTo(3L); // 80000 최대
    }

    @Test
    @DisplayName("전량 취소/반품 주문은 대표상태가 그 종결값이고 금액에서 제외 (노션 S-2 규칙3)")
    void listAllTerminalStatus() {
        Order o20 = order(20L);
        List<OrderItem> items = List.of(
                item(20L, 1L, OrderItemStatus.CANCELLED, 30000, 1),
                item(20L, 2L, OrderItemStatus.CANCELLED, 20000, 1));
        List<Product> products = List.of(product(1L), product(2L));

        when(orderItemRepository.countSellerOrderTabs(eq(BRAND_ID), any(), any(), any()))
                .thenReturn(List.of(tab("CLAIM", 1)));
        when(orderItemRepository.findSellerOrderIdsByTab(eq(BRAND_ID), any(), any(), any(), any(),
                anyInt(), anyLong())).thenReturn(List.of(20L));
        when(orderRepository.findAllById(any())).thenReturn(List.of(o20));
        when(orderItemRepository.findSellerItemsByOrderIds(eq(BRAND_ID), any())).thenReturn(items);
        when(productRepository.findAllById(any())).thenReturn(products);

        SellerOrderListResponse res = service.list(BRAND_ID, null, 0, 20);

        SellerOrderListResponse.Row row = res.content().get(0);
        assertThat(row.status()).isEqualTo("CANCELLED");
        assertThat(row.myItemsAmount()).isZero(); // CANCELLED 전량 제외
    }

    @Test
    @DisplayName("잘못된 status 탭·size>100은 400 ORDER_INVALID_PARAM (노션 S-2)")
    void listRejectsInvalidParams() {
        assertThatThrownBy(() -> service.list(BRAND_ID, "BOGUS", 0, 20))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ORDER_INVALID_PARAM);
        assertThatThrownBy(() -> service.list(BRAND_ID, null, 0, 101))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ORDER_INVALID_PARAM);
    }

    // ── I-29 (노션 I-29) — S-2와 같은 파생 + items 배열 ──

    @Test
    @DisplayName("I-29: items에 orderItemId·status·activeClaimStatus를 싣고 S-2 파생을 상속한다")
    void listInternalCarriesItems() {
        Order o10 = order(10L);
        List<OrderItem> items = List.of(
                item(10L, 1L, OrderItemStatus.ORDERED, 30000, 1),
                item(10L, 2L, OrderItemStatus.CANCEL_REQUESTED, 50000, 1));
        List<Product> products = List.of(product(1L), product(2L));

        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        when(orderItemRepository.countSellerOrderTabs(eq(BRAND_ID), any(), any(), any()))
                .thenReturn(List.of(tab("CLAIM", 1)));
        when(orderItemRepository.findSellerOrderIdsByTab(eq(BRAND_ID), any(), any(), any(), any(),
                anyInt(), anyLong())).thenReturn(List.of(10L));
        when(orderRepository.findAllById(any())).thenReturn(List.of(o10));
        when(orderItemRepository.findSellerItemsByOrderIds(eq(BRAND_ID), any())).thenReturn(items);
        when(productRepository.findAllById(any())).thenReturn(products);

        SellerOrderInternalResponse res = service.listInternal(
                BRAND_ID, null, null, AnalysisPeriod.optional(null, null), 20, 0);

        assertThat(res.total()).isEqualTo(1L);
        assertThat(res.tabCounts()).containsEntry("ALL", 1L).containsEntry("CLAIM", 1L)
                .containsEntry("ORDERED", 0L); // 0건 탭도 키가 있어야 한다
        SellerOrderInternalResponse.Row row = res.rows().get(0);
        assertThat(row.status()).isEqualTo("ORDERED");           // 가장 뒤진 단계 (S-2 파생 상속)
        assertThat(row.claimStatus()).isEqualTo("CANCEL_REQUESTED");
        assertThat(row.myItemsAmount()).isEqualTo(80000);
        assertThat(row.items()).hasSize(2);
        assertThat(row.items().get(0).orderItemId()).isEqualTo(5001L);
        assertThat(row.items().get(0).name()).isEqualTo("상품1");  // 현재 상품명 우선
        assertThat(row.items().get(0).activeClaimStatus()).isNull();
        assertThat(row.items().get(1).status()).isEqualTo("CANCEL_REQUESTED");
        assertThat(row.items().get(1).activeClaimStatus()).isEqualTo("CANCEL_REQUESTED");
    }

    @Test
    @DisplayName("I-29: 종결된 클레임은 activeClaimStatus가 null이다 — status에 이미 드러나므로")
    void listInternalActiveClaimExcludesTerminal() {
        Order o20 = order(20L);
        List<OrderItem> items = List.of(item(20L, 1L, OrderItemStatus.CANCELLED, 30000, 1));
        List<Product> products = List.of(product(1L));

        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        when(orderItemRepository.countSellerOrderTabs(eq(BRAND_ID), any(), any(), any()))
                .thenReturn(List.of(tab("CLAIM", 1)));
        when(orderItemRepository.findSellerOrderIdsByTab(eq(BRAND_ID), any(), any(), any(), any(),
                anyInt(), anyLong())).thenReturn(List.of(20L));
        when(orderRepository.findAllById(any())).thenReturn(List.of(o20));
        when(orderItemRepository.findSellerItemsByOrderIds(eq(BRAND_ID), any())).thenReturn(items);
        when(productRepository.findAllById(any())).thenReturn(products);

        SellerOrderInternalResponse res = service.listInternal(
                BRAND_ID, null, null, AnalysisPeriod.optional(null, null), 20, 0);

        SellerOrderInternalResponse.Item item = res.rows().get(0).items().get(0);
        assertThat(item.status()).isEqualTo("CANCELLED");
        assertThat(item.activeClaimStatus()).isNull();
    }

    @Test
    @DisplayName("I-29: 자사 주문 0건도 200 + 빈 rows·total 0·tabCounts 전부 0 (노션 I-29)")
    void listInternalEmptyIsNormal() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        when(orderItemRepository.countSellerOrderTabs(eq(BRAND_ID), any(), any(), any()))
                .thenReturn(List.of());
        when(orderItemRepository.findSellerOrderIdsByTab(eq(BRAND_ID), any(), any(), any(), any(),
                anyInt(), anyLong())).thenReturn(List.of());

        SellerOrderInternalResponse res = service.listInternal(
                BRAND_ID, null, 999L, AnalysisPeriod.optional(null, null), 20, 0);

        assertThat(res.rows()).isEmpty();
        assertThat(res.total()).isZero();
        assertThat(res.tabCounts()).containsEntry("ALL", 0L).containsEntry("ORDERED", 0L)
                .containsEntry("SHIPPING", 0L).containsEntry("DELIVERED", 0L).containsEntry("CLAIM", 0L);
    }

    @Test
    @DisplayName("I-29: 없는 brandId는 404 BRAND_NOT_FOUND")
    void listInternalRejectsUnknownBrand() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.listInternal(
                BRAND_ID, null, null, AnalysisPeriod.optional(null, null), 20, 0))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BRAND_NOT_FOUND);
    }

    // ── I-30 (노션 I-30) — 발송 처리. 판정 순서가 계약이다 ──

    private static OrderItem shippableItem(OrderItemStatus status) {
        OrderItem i = mock(OrderItem.class);
        lenient().when(i.getId()).thenReturn(5551L);
        lenient().when(i.getOrderId()).thenReturn(500L);
        lenient().when(i.getStatus()).thenReturn(status);
        return i;
    }

    @Test
    @DisplayName("I-30: ORDERED 아이템을 SHIPPING으로 전이하고 fromStatus를 함께 돌려준다")
    void shipItemTransitions() {
        OrderItem target = shippableItem(OrderItemStatus.ORDERED);

        when(orderItemRepository.findOwnedByBrand(5551L, BRAND_ID)).thenReturn(Optional.of(target));
        when(statusChanger.shipBySeller(eq(target), any(), any())).thenReturn(true);

        SellerShipResponse res = service.shipItem(BRAND_ID, 5551L,
                new SellerShipRequest("SHIPPING", "오늘 출고"));

        assertThat(res.orderItemId()).isEqualTo(5551L);
        assertThat(res.fromStatus()).isEqualTo("ORDERED");
        assertThat(res.toStatus()).isEqualTo("SHIPPING");
        assertThat(res.changedAt()).isNotNull();
    }

    @Test
    @DisplayName("I-30: 이미 SHIPPING이면 409 — 멱등 200을 내지 않는다")
    void shipItemRejectsAlreadyShipped() {
        OrderItem target = shippableItem(OrderItemStatus.SHIPPING);

        when(orderItemRepository.findOwnedByBrand(5551L, BRAND_ID)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.shipItem(BRAND_ID, 5551L, new SellerShipRequest("SHIPPING", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ORDER_ALREADY_SHIPPED);
    }

    @Test
    @DisplayName("I-30: 경합에 져 조건부 UPDATE가 0건이면 그것도 409다")
    void shipItemRejectsLostRace() {
        OrderItem target = shippableItem(OrderItemStatus.ORDERED);

        when(orderItemRepository.findOwnedByBrand(5551L, BRAND_ID)).thenReturn(Optional.of(target));
        when(statusChanger.shipBySeller(eq(target), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.shipItem(BRAND_ID, 5551L, new SellerShipRequest("SHIPPING", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ORDER_ALREADY_SHIPPED);
    }

    @Test
    @DisplayName("I-30: 활성 클레임 등 ORDERED가 아닌 상태는 400 ORDER_INVALID_TRANSITION")
    void shipItemRejectsInvalidTransition() {
        OrderItem claimed = shippableItem(OrderItemStatus.CANCEL_REQUESTED);

        when(orderItemRepository.findOwnedByBrand(5551L, BRAND_ID)).thenReturn(Optional.of(claimed));

        assertThatThrownBy(() -> service.shipItem(BRAND_ID, 5551L, new SellerShipRequest("SHIPPING", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
    }

    @Test
    @DisplayName("I-30: 타 브랜드·미존재 아이템은 403이 아니라 404로 존재를 은닉한다")
    void shipItemHidesForeignItem() {
        when(orderItemRepository.findOwnedByBrand(5551L, BRAND_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.shipItem(BRAND_ID, 5551L, new SellerShipRequest("SHIPPING", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ORDER_ITEM_NOT_FOUND);
    }

    @Test
    @DisplayName("I-30: 어휘 밖(PREPARING)은 VALIDATION_ERROR, 어휘엔 있으나 미허용(DELIVERED)은 전이 오류")
    void shipItemSplitsVocabularyAndTransitionErrors() {
        // 어휘 판정이 소유권 조회보다 먼저다 — 조회 스텁 없이도 걸린다
        assertThatThrownBy(() -> service.shipItem(BRAND_ID, 5551L, new SellerShipRequest("PREPARING", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThatThrownBy(() -> service.shipItem(BRAND_ID, 5551L, new SellerShipRequest("SHIPPED", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);

        assertThatThrownBy(() -> service.shipItem(BRAND_ID, 5551L, new SellerShipRequest("DELIVERED", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
    }

    @Test
    @DisplayName("I-29: 기간은 선택 — 누락은 통과하고 역전·형식 오류만 INVALID_PERIOD")
    void optionalPeriodRejectsOnlyMalformed() {
        assertThat(AnalysisPeriod.optional(null, null).from()).isNull();
        assertThat(AnalysisPeriod.optional("2026-08-01", null).to()).isNull();

        assertThatThrownBy(() -> AnalysisPeriod.optional("2026-08-05", "2026-08-01"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PERIOD);
        assertThatThrownBy(() -> AnalysisPeriod.optional("08/01/2026", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PERIOD);
    }
}
