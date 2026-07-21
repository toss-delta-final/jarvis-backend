package com.jarvis.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.jarvis.address.AddressRepository;
import com.jarvis.cart.CartItemRepository;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.member.MemberRepository;
import com.jarvis.order.dto.InternalOrderListResponse;
import com.jarvis.order.dto.InternalOrderStatusResponse;
import com.jarvis.product.ProductChangeLogRepository;
import com.jarvis.product.ProductOptionRepository;
import com.jarvis.product.ProductRepository;
import com.jarvis.review.ReviewRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

/** I-4·I-19 챗봇 조회 (05 §I-4·§I-19) — status 어휘·회원 존재·응답 계약 검증 */
@ExtendWith(MockitoExtension.class)
class OrderChatQueryTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock CartItemRepository cartItemRepository;
    @Mock ProductRepository productRepository;
    @Mock ProductOptionRepository productOptionRepository;
    @Mock ProductChangeLogRepository productChangeLogRepository;
    @Mock com.jarvis.category.CategoryRepository categoryRepository;
    @Mock AddressRepository addressRepository;
    @Mock MemberRepository memberRepository;
    @Mock ReviewRepository reviewRepository;
    @Mock PaymentService paymentService;
    @Mock OrderStatusChanger statusChanger;

    @InjectMocks OrderService orderService;

    private Order paidOrder(Long id) {
        Order order = Order.create(1L, "MOCK_CARD", 24000, "김자비", "010-1234-5678",
                "06236", "서울시 강남구", null, null);
        ReflectionTestUtils.setField(order, "id", id);
        ReflectionTestUtils.setField(order, "createdAt", LocalDateTime.of(2026, 7, 17, 12, 0));
        ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);
        return order;
    }

    private OrderItem item(Long orderId, int price, int quantity, OrderItemStatus status) {
        OrderItem item = OrderItem.pending(orderId, 10L, "상품10", null, price, price, quantity,
                LocalDateTime.of(2026, 7, 17, 12, 0));
        ReflectionTestUtils.setField(item, "status", status);
        return item;
    }

    @Test
    @DisplayName("I-19 — 우리 상태명 6종 외 status는 400 ORDER_INVALID_PARAM")
    void invalidStatusRejected() {
        when(memberRepository.existsById(1L)).thenReturn(true);

        assertThatThrownBy(() -> orderService.listForChat(1L, "PAID"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ORDER_INVALID_PARAM);
    }

    @Test
    @DisplayName("I-19 — 미존재 회원은 404 MEMBER_NOT_FOUND (200 빈 목록 아님)")
    void listUnknownMemberNotFound() {
        when(memberRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> orderService.listForChat(99L, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    @DisplayName("I-4 — 미존재 회원은 404 MEMBER_NOT_FOUND")
    void summaryUnknownMemberNotFound() {
        when(memberRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> orderService.statusSummary(99L, 3))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    @DisplayName("I-19 — 주문 없으면 빈 목록 200 (빈 장바구니와 같은 관성)")
    void emptyOrders() {
        when(memberRepository.existsById(1L)).thenReturn(true);
        when(orderRepository.findAllByMemberIdOrderByIdDesc(eq(1L), any(PageRequest.class)))
                .thenReturn(Page.empty());

        InternalOrderListResponse response = orderService.listForChat(1L, "DELIVERED");

        assertThat(response.orders()).isEmpty();
    }

    @Test
    @DisplayName("I-19 — representativeStatus는 enum 코드, itemsTotal은 스냅샷 가격×수량 합")
    void listSummaryFields() {
        when(memberRepository.existsById(1L)).thenReturn(true);
        when(orderRepository.findAllByMemberIdOrderByIdDesc(eq(1L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(paidOrder(1L))));
        when(orderItemRepository.findAllByOrderIdIn(List.of(1L)))
                .thenReturn(List.of(item(1L, 12000, 2, OrderItemStatus.SHIPPING)));

        InternalOrderListResponse response = orderService.listForChat(1L, null);

        InternalOrderListResponse.Summary summary = response.orders().get(0);
        assertThat(summary.representativeStatus()).isEqualTo("SHIPPING");
        assertThat(summary.itemsTotal()).isEqualTo(24000);
        assertThat(summary.shippingFee()).isZero();
        assertThat(summary.totalAmount()).isEqualTo(24000);
    }

    @Test
    @DisplayName("I-4 — representativeStatus는 한국어 표시 문자열 (07-18 노션 재확인)")
    void summaryRepresentativeStatusKorean() {
        when(memberRepository.existsById(1L)).thenReturn(true);
        when(orderRepository.findAllByMemberIdOrderByIdDesc(eq(1L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(paidOrder(1L))));
        when(orderItemRepository.findAllByOrderIdIn(List.of(1L)))
                .thenReturn(List.of(item(1L, 12000, 2, OrderItemStatus.SHIPPING)));

        InternalOrderStatusResponse response = orderService.statusSummary(1L, 3);

        assertThat(response.orders().get(0).representativeStatus()).isEqualTo("배송중");
    }
}
