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
import com.jarvis.order.dto.RetryPaymentRequest;
import com.jarvis.product.ProductChangeLogRepository;
import com.jarvis.product.ProductOptionRepository;
import com.jarvis.product.ProductRepository;
import com.jarvis.review.ReviewRepository;
import java.time.LocalDateTime;
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
}
