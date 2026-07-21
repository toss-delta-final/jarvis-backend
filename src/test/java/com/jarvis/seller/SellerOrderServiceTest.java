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

import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.order.Order;
import com.jarvis.order.OrderItem;
import com.jarvis.order.OrderItemRepository;
import com.jarvis.order.OrderItemStatus;
import com.jarvis.order.OrderRepository;
import com.jarvis.product.Product;
import com.jarvis.product.ProductRepository;
import com.jarvis.seller.dto.SellerOrderListResponse;
import java.time.LocalDateTime;
import java.util.List;
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

    @InjectMocks private SellerOrderService service;

    private static OrderItemRepository.StatusCountRow tab(String bucket, long cnt) {
        return new OrderItemRepository.StatusCountRow() {
            public String getBucket() { return bucket; }
            public Long getCnt() { return cnt; }
        };
    }

    private static OrderItem item(long orderId, long productId, OrderItemStatus status, int price, int qty) {
        OrderItem i = mock(OrderItem.class);
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

        when(orderItemRepository.countSellerOrderTabs(BRAND_ID))
                .thenReturn(List.of(tab("ORDERED", 2), tab("CLAIM", 1)));
        when(orderItemRepository.findSellerOrderIdsByTab(eq(BRAND_ID), any(), anyInt(), anyLong()))
                .thenReturn(List.of(10L, 11L));
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

        when(orderItemRepository.countSellerOrderTabs(BRAND_ID)).thenReturn(List.of(tab("CLAIM", 1)));
        when(orderItemRepository.findSellerOrderIdsByTab(eq(BRAND_ID), any(), anyInt(), anyLong()))
                .thenReturn(List.of(20L));
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
}
