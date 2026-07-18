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
import com.jarvis.order.dto.InternalOrderListResponse;
import com.jarvis.product.ProductChangeLogRepository;
import com.jarvis.product.ProductOptionRepository;
import com.jarvis.product.ProductRepository;
import com.jarvis.review.ReviewRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/** I-19 구매 이력 목록 (05 §I-19) — status 어휘 검증 */
@ExtendWith(MockitoExtension.class)
class OrderChatQueryTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock CartItemRepository cartItemRepository;
    @Mock ProductRepository productRepository;
    @Mock ProductOptionRepository productOptionRepository;
    @Mock ProductChangeLogRepository productChangeLogRepository;
    @Mock AddressRepository addressRepository;
    @Mock ReviewRepository reviewRepository;
    @Mock PaymentService paymentService;
    @Mock OrderStatusChanger statusChanger;

    @InjectMocks OrderService orderService;

    @Test
    @DisplayName("I-19 — 우리 상태명 6종 외 status는 400 VALIDATION_ERROR")
    void invalidStatusRejected() {
        assertThatThrownBy(() -> orderService.listForChat(1L, "PAID"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("I-19 — 주문 없으면 빈 목록 200 (빈 장바구니와 같은 관성)")
    void emptyOrders() {
        when(orderRepository.findAllByMemberIdOrderByIdDesc(eq(1L), any(PageRequest.class)))
                .thenReturn(Page.empty());

        InternalOrderListResponse response = orderService.listForChat(1L, "DELIVERED");

        assertThat(response.orders()).isEmpty();
    }
}
