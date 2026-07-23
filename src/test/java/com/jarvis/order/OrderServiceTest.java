package com.jarvis.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jarvis.address.AddressRepository;
import com.jarvis.cart.CartItem;
import com.jarvis.cart.CartItemRepository;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.member.MemberRepository;
import com.jarvis.order.PaymentService.PaymentResult;
import com.jarvis.order.dto.OrderCreateRequest;
import com.jarvis.order.dto.OrderCreateResponse;
import com.jarvis.order.dto.OrderDetailResponse;
import com.jarvis.order.dto.OrderListResponse;
import com.jarvis.order.dto.RetryPaymentRequest;
import com.jarvis.product.ProductChangeLogRepository;
import com.jarvis.product.ProductOptionRepository;
import com.jarvis.product.ProductRepository;
import com.jarvis.review.ReviewRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock CartItemRepository cartItemRepository;
    @Mock ProductRepository productRepository;
    @Mock ProductOptionRepository productOptionRepository;
    @Mock ProductChangeLogRepository productChangeLogRepository;
    @Mock AddressRepository addressRepository;
    @Mock MemberRepository memberRepository;
    @Mock ReviewRepository reviewRepository;
    @Mock PaymentService paymentService;
    @Mock OrderStatusChanger statusChanger;

    @Captor ArgumentCaptor<List<OrderItem>> itemsCaptor;

    @InjectMocks OrderService orderService;

    private com.jarvis.product.Product product;

    @BeforeEach
    void setUp() {
        product = product(10L, 12000, 15000);
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 1L);
            ReflectionTestUtils.setField(order, "createdAt", LocalDateTime.of(2026, 7, 17, 12, 0));
            return order;
        });
        lenient().when(orderItemRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        // 목이지만 실제 전이 부수효과는 재현 — 서비스가 PAID 여부로 후속 처리(장바구니 삭제)를 분기하므로
        lenient().doAnswer(inv -> {
            Order order = inv.getArgument(0);
            order.markPaid(inv.getArgument(2));
            return null;
        }).when(statusChanger).paymentSucceeded(any(Order.class), anyList(), any(LocalDateTime.class));
    }

    private com.jarvis.product.Product product(Long id, int price, int originalPrice) {
        com.jarvis.product.Product p = org.mockito.Mockito.mock(com.jarvis.product.Product.class,
                org.mockito.Mockito.withSettings()
                        .strictness(org.mockito.quality.Strictness.LENIENT));
        when(p.getId()).thenReturn(id);
        when(p.getPrice()).thenReturn(price);
        when(p.getOriginalPrice()).thenReturn(originalPrice);
        when(p.getName()).thenReturn("상품" + id);
        when(p.getStatus()).thenReturn(com.jarvis.product.ProductStatus.ON_SALE);
        return p;
    }

    private OrderCreateRequest directRequest(String paymentMethod, int quantity) {
        return new OrderCreateRequest(null,
                List.of(new OrderCreateRequest.OrderLine(10L, null, quantity)),
                null,
                new OrderCreateRequest.AddressInput("김자비", "010-1234-5678", "06236", "서울시 강남구", null),
                null, paymentMethod);
    }

    @Test
    @DisplayName("O-1 바로 구매 성공 — 총액=스냅샷 합, 재고 차감, PAID 전이, 장바구니 미접촉")
    void createDirectPaid() {
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));
        when(productOptionRepository.findAllByProductIdOrderByIdAsc(10L)).thenReturn(List.of());
        when(paymentService.pay("MOCK_CARD", 24000)).thenReturn(PaymentResult.approved());
        when(productRepository.deductStock(10L, 2)).thenReturn(1);
        when(productRepository.findStockQuantity(10L)).thenReturn(Optional.of(98));

        OrderCreateResponse response = orderService.create(1L, directRequest("MOCK_CARD", 2));

        assertThat(response.orderNo()).isEqualTo("ORD-20260717-1");
        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getTotalAmount()).isEqualTo(24000);
        verify(statusChanger).logOrderCreated(any(Order.class));
        verify(statusChanger).paymentSucceeded(any(Order.class), anyList(), any(LocalDateTime.class));
        verify(cartItemRepository, never()).deleteAll(anyList());
        verify(productChangeLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("O-1 MOCK_FAIL — PAYMENT_FAILED 기록, 재고·장바구니 미접촉, 아이템은 PENDING 잔존")
    void createPaymentFailed() {
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));
        when(productOptionRepository.findAllByProductIdOrderByIdAsc(10L)).thenReturn(List.of());
        when(paymentService.pay(eq("MOCK_FAIL"), anyInt()))
                .thenReturn(PaymentResult.declined("MOCK_DECLINED"));

        orderService.create(1L, directRequest("MOCK_FAIL", 1));

        verify(statusChanger).paymentFailed(any(Order.class), eq("MOCK_DECLINED"));
        verify(statusChanger, never()).paymentSucceeded(any(), anyList(), any());
        verify(productRepository, never()).deductStock(anyLong(), anyInt());
        verify(orderItemRepository).saveAll(itemsCaptor.capture());
        assertThat(itemsCaptor.getValue()).allMatch(i -> i.getStatus() == OrderItemStatus.PENDING);
    }

    @Test
    @DisplayName("O-1 재고 부족 — OUT_OF_STOCK 결제 실패 + 기차감분 보상 복원")
    void createOutOfStock() {
        com.jarvis.product.Product second = product(20L, 5000, 5000);
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));
        when(productRepository.findById(20L)).thenReturn(Optional.of(second));
        when(productOptionRepository.findAllByProductIdOrderByIdAsc(anyLong())).thenReturn(List.of());
        when(paymentService.pay(anyString(), anyInt())).thenReturn(PaymentResult.approved());
        when(productRepository.deductStock(10L, 1)).thenReturn(1);
        when(productRepository.findStockQuantity(10L)).thenReturn(Optional.of(99));
        when(productRepository.deductStock(20L, 1)).thenReturn(0);

        OrderCreateRequest request = new OrderCreateRequest(null,
                List.of(new OrderCreateRequest.OrderLine(10L, null, 1),
                        new OrderCreateRequest.OrderLine(20L, null, 1)),
                null,
                new OrderCreateRequest.AddressInput("김자비", "010-1234-5678", "06236", "서울", null),
                null, "MOCK_CARD");
        orderService.create(1L, request);

        verify(productRepository).restoreStock(10L, 1);
        verify(statusChanger).paymentFailed(any(Order.class), eq("OUT_OF_STOCK"));
    }

    @Test
    @DisplayName("O-1 — cartItemIds와 items 둘 다/둘 다 없음은 400")
    void createSourceXor() {
        OrderCreateRequest both = new OrderCreateRequest(List.of(1L),
                List.of(new OrderCreateRequest.OrderLine(10L, null, 1)), null,
                new OrderCreateRequest.AddressInput("a", "b", "c", "d", null), null, "MOCK_CARD");
        assertThatThrownBy(() -> orderService.create(1L, both))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("O-1 장바구니 경유 성공 — 해당 cart 행 삭제")
    void createFromCartDeletesLines() {
        CartItem cartItem = CartItem.forMember(1L, 10L, null, 2);
        ReflectionTestUtils.setField(cartItem, "id", 5L);
        when(cartItemRepository.findAllById(List.of(5L))).thenReturn(List.of(cartItem));
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));
        when(productOptionRepository.findAllByProductIdOrderByIdAsc(10L)).thenReturn(List.of());
        when(paymentService.pay("MOCK_CARD", 24000)).thenReturn(PaymentResult.approved());
        when(productRepository.deductStock(10L, 2)).thenReturn(1);
        when(productRepository.findStockQuantity(10L)).thenReturn(Optional.of(98));

        OrderCreateRequest request = new OrderCreateRequest(List.of(5L), null, null,
                new OrderCreateRequest.AddressInput("김자비", "010-1234-5678", "06236", "서울", null),
                null, "MOCK_CARD");
        orderService.create(1L, request);

        verify(cartItemRepository).deleteAll(List.of(cartItem));
    }

    @Test
    @DisplayName("O-1 — 남의 장바구니 행은 CART_ITEM_NOT_FOUND")
    void createFromCartRejectsForeignLines() {
        CartItem foreign = CartItem.forMember(2L, 10L, null, 1);
        when(cartItemRepository.findAllById(List.of(5L))).thenReturn(List.of(foreign));

        OrderCreateRequest request = new OrderCreateRequest(List.of(5L), null, null,
                new OrderCreateRequest.AddressInput("a", "b", "c", "d", null), null, "MOCK_CARD");
        assertThatThrownBy(() -> orderService.create(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CART_ITEM_NOT_FOUND);
    }

    @Test
    @DisplayName("O-2 — PENDING/PAYMENT_FAILED가 아니면 ORDER_INVALID_TRANSITION")
    void retryRejectsPaidOrder() {
        Order order = Order.create(1L, "MOCK_CARD", 1000, "r", "p", "z", "a1", null, null);
        ReflectionTestUtils.setField(order, "id", 1L);
        ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.retryPayment(1L, 1L, new RetryPaymentRequest("MOCK_CARD")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
    }

    @Test
    @DisplayName("O-2 재결제 진입은 비관적 락으로 주문을 로드한다 (동시 재결제 직렬화)")
    void retryUsesPessimisticLock() {
        Order order = Order.create(1L, "MOCK_CARD", 24000, "r", "p", "z", "a1", null, null);
        ReflectionTestUtils.setField(order, "id", 1L);
        ReflectionTestUtils.setField(order, "createdAt", LocalDateTime.of(2026, 7, 17, 12, 0));
        ReflectionTestUtils.setField(order, "status", OrderStatus.PAYMENT_FAILED);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(orderItemRepository.findAllByOrderId(1L)).thenReturn(List.of());
        when(paymentService.pay("MOCK_CARD", 24000)).thenReturn(PaymentResult.approved());
        when(cartItemRepository.findAllByMemberId(1L)).thenReturn(List.of());

        orderService.retryPayment(1L, 1L, new RetryPaymentRequest("MOCK_CARD"));

        verify(orderRepository).findByIdForUpdate(1L);
        verify(orderRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("O-1 — 품절 로그는 전 품목 차감 성공 시에만 기록, 일부 실패로 보상 복원되면 미기록")
    void stockOutLogOnlyOnFullSuccess() {
        com.jarvis.product.Product second = product(20L, 5000, 5000);
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));
        when(productRepository.findById(20L)).thenReturn(Optional.of(second));
        when(productOptionRepository.findAllByProductIdOrderByIdAsc(anyLong())).thenReturn(List.of());
        when(paymentService.pay(anyString(), anyInt())).thenReturn(PaymentResult.approved());
        // 10L 차감 성공 후 재고 0 도달(품절 로그 후보) → 20L 차감 실패로 전체 롤백
        when(productRepository.deductStock(10L, 1)).thenReturn(1);
        when(productRepository.findStockQuantity(10L)).thenReturn(Optional.of(0));
        when(productRepository.deductStock(20L, 1)).thenReturn(0);

        OrderCreateRequest request = new OrderCreateRequest(null,
                List.of(new OrderCreateRequest.OrderLine(10L, null, 1),
                        new OrderCreateRequest.OrderLine(20L, null, 1)),
                null,
                new OrderCreateRequest.AddressInput("김자비", "010-1234-5678", "06236", "서울", null),
                null, "MOCK_CARD");
        orderService.create(1L, request);

        verify(productRepository).restoreStock(10L, 1);
        verify(statusChanger).paymentFailed(any(Order.class), eq("OUT_OF_STOCK"));
        // 허위 품절 로그가 남지 않아야 함
        verify(productChangeLogRepository, never()).saveAll(anyList());
        verify(productChangeLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("O-1 — 금액이 INT 범위를 넘으면 VALIDATION_ERROR (int 곱셈 오버플로 방지)")
    void rejectsOverflowingAmount() {
        com.jarvis.product.Product pricey = product(30L, 100_000_000, 100_000_000);
        when(productRepository.findById(30L)).thenReturn(Optional.of(pricey));
        when(productOptionRepository.findAllByProductIdOrderByIdAsc(30L)).thenReturn(List.of());

        OrderCreateRequest request = new OrderCreateRequest(null,
                List.of(new OrderCreateRequest.OrderLine(30L, null, 99)), // 9.9e9 > Integer.MAX_VALUE
                null,
                new OrderCreateRequest.AddressInput("김자비", "010-1234-5678", "06236", "서울", null),
                null, "MOCK_CARD");
        assertThatThrownBy(() -> orderService.create(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        verify(paymentService, never()).pay(anyString(), anyInt());
    }

    // ---- O-3 · O-4 조회 ----

    private Order savedOrder(Long memberId, Long orderId, OrderStatus status) {
        Order order = Order.create(memberId, "MOCK_CARD", 24000,
                "김자비", "010-1234-5678", "06236", "서울시 강남구", "101동", "문앞에 놔주세요");
        ReflectionTestUtils.setField(order, "id", orderId);
        ReflectionTestUtils.setField(order, "createdAt", LocalDateTime.of(2026, 7, 17, 12, 0));
        ReflectionTestUtils.setField(order, "status", status);
        return order;
    }

    private OrderItem item(Long id, Long orderId, Long productId, String productName, String optionName,
                           int price, int originalPrice, int quantity, OrderItemStatus status) {
        OrderItem item = OrderItem.pending(orderId, productId, productName, optionName,
                price, originalPrice, quantity, LocalDateTime.of(2026, 7, 17, 12, 0));
        ReflectionTestUtils.setField(item, "id", id);
        ReflectionTestUtils.setField(item, "status", status);
        return item;
    }

    @Test
    @DisplayName("O-3 — 페이징 파라미터 전달·메타데이터, orderNo 파생, 대표 상태·아이템 요약 매핑")
    void listMapsPagingAndItems() {
        Order order = savedOrder(1L, 7L, OrderStatus.PAID);
        OrderItem line = item(100L, 7L, 10L, "상품10", "옵션A", 12000, 15000, 2, OrderItemStatus.ORDERED);
        when(orderRepository.findAllByMemberIdOrderByIdDesc(1L, PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(order), PageRequest.of(0, 10), 11));
        when(orderItemRepository.findAllByOrderIdIn(List.of(7L))).thenReturn(List.of(line));
        when(product.getImageUrl()).thenReturn("http://img/10.jpg");
        when(productRepository.findAllById(List.of(10L))).thenReturn(List.of(product));

        OrderListResponse response = orderService.list(1L, 0, 10);

        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isEqualTo(11);
        assertThat(response.totalPages()).isEqualTo(2);
        OrderListResponse.Summary summary = response.content().get(0);
        assertThat(summary.orderId()).isEqualTo(7L);
        assertThat(summary.orderNo()).isEqualTo("ORD-20260717-7");
        assertThat(summary.representativeStatus()).isEqualTo("ORDERED");
        assertThat(summary.totalAmount()).isEqualTo(24000);
        assertThat(summary.orderedAt()).isEqualTo(
                LocalDateTime.of(2026, 7, 17, 12, 0).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime());
        assertThat(summary.items()).hasSize(1);
        OrderListResponse.ItemSummary itemSummary = summary.items().get(0);
        assertThat(itemSummary.orderItemId()).isEqualTo(100L);
        assertThat(itemSummary.productId()).isEqualTo(10L);
        assertThat(itemSummary.productName()).isEqualTo("상품10");
        assertThat(itemSummary.optionName()).isEqualTo("옵션A");
        assertThat(itemSummary.price()).isEqualTo(12000);
        assertThat(itemSummary.quantity()).isEqualTo(2);
        assertThat(itemSummary.status()).isEqualTo("ORDERED");
        assertThat(itemSummary.imageUrl()).isEqualTo("http://img/10.jpg");
    }

    @Test
    @DisplayName("O-3 — 주문 없으면 빈 content, 아이템 일괄 조회 미실행")
    void listEmptyPage() {
        when(orderRepository.findAllByMemberIdOrderByIdDesc(1L, PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        OrderListResponse response = orderService.list(1L, 0, 10);

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
        verify(orderItemRepository, never()).findAllByOrderIdIn(anyList());
    }

    @Test
    @DisplayName("O-4 — 배송지 스냅샷·paidAt·아이템별 가능 액션 매핑, 리뷰 작성분은 canReview=false")
    void detailMapsActionsAndSnapshot() {
        Order order = savedOrder(1L, 7L, OrderStatus.PAID);
        ReflectionTestUtils.setField(order, "paidAt", LocalDateTime.of(2026, 7, 17, 12, 1));
        when(orderRepository.findById(7L)).thenReturn(Optional.of(order));
        OrderItem delivered = item(100L, 7L, 10L, "상품10", "옵션A", 12000, 15000, 2, OrderItemStatus.DELIVERED);
        OrderItem confirmed = item(200L, 7L, 20L, "상품20", null, 5000, 5000, 1, OrderItemStatus.CONFIRMED);
        when(orderItemRepository.findAllByOrderId(7L)).thenReturn(List.of(delivered, confirmed));
        when(reviewRepository.findOrderItemIdsIn(List.of(100L, 200L))).thenReturn(List.of(200L));
        com.jarvis.product.Product second = product(20L, 5000, 5000);
        when(product.getImageUrl()).thenReturn("http://img/10.jpg");
        when(second.getImageUrl()).thenReturn("http://img/20.jpg");
        when(productRepository.findAllById(List.of(10L, 20L))).thenReturn(List.of(product, second));

        OrderDetailResponse response = orderService.detail(1L, 7L);

        assertThat(response.orderId()).isEqualTo(7L);
        assertThat(response.orderNo()).isEqualTo("ORD-20260717-7");
        assertThat(response.status()).isEqualTo("PAID");
        assertThat(response.representativeStatus()).isEqualTo("DELIVERED");
        assertThat(response.paymentMethod()).isEqualTo("MOCK_CARD");
        assertThat(response.totalAmount()).isEqualTo(24000);
        assertThat(response.paidAt()).isEqualTo(
                LocalDateTime.of(2026, 7, 17, 12, 1).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime());
        assertThat(response.address().recipient()).isEqualTo("김자비");
        assertThat(response.address().zipCode()).isEqualTo("06236");
        assertThat(response.address().address1()).isEqualTo("서울시 강남구");
        assertThat(response.address().address2()).isEqualTo("101동");
        assertThat(response.deliveryRequest()).isEqualTo("문앞에 놔주세요");

        assertThat(response.items()).hasSize(2);
        OrderDetailResponse.Item first = response.items().get(0);
        assertThat(first.orderItemId()).isEqualTo(100L);
        assertThat(first.originalPrice()).isEqualTo(15000);
        assertThat(first.imageUrl()).isEqualTo("http://img/10.jpg");
        assertThat(first.canCancel()).isFalse();
        assertThat(first.canReturn()).isTrue();
        assertThat(first.canReview()).isTrue(); // DELIVERED + 리뷰 미작성
        OrderDetailResponse.Item secondItem = response.items().get(1);
        assertThat(secondItem.canCancel()).isFalse();
        assertThat(secondItem.canReturn()).isFalse();
        assertThat(secondItem.canReview()).isFalse(); // CONFIRMED이지만 리뷰 기작성
    }

    @Test
    @DisplayName("O-4 — 존재하지 않는 주문은 ORDER_NOT_FOUND")
    void detailNotFound() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.detail(1L, 99L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_NOT_FOUND);
    }

    @Test
    @DisplayName("O-4 — 다른 사용자의 주문은 존재 노출 없이 ORDER_NOT_FOUND")
    void detailRejectsForeignOrder() {
        Order foreign = savedOrder(2L, 7L, OrderStatus.PAID);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> orderService.detail(1L, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_NOT_FOUND);
        verify(orderItemRepository, never()).findAllByOrderId(anyLong());
    }
}
