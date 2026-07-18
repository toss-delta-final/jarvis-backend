package com.jarvis.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.order.dto.ClaimRequest;
import com.jarvis.order.dto.ClaimResponse;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ClaimServiceTest {

    @Mock ClaimRepository claimRepository;
    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock OrderStatusChanger statusChanger;

    @InjectMocks ClaimService claimService;

    private OrderItem item;
    private Order order;

    @BeforeEach
    void setUp() {
        item = OrderItem.pending(1L, 10L, "상품", null, 12000, 15000, 1, LocalDateTime.now());
        ReflectionTestUtils.setField(item, "id", 100L);
        order = Order.create(1L, "MOCK_CARD", 12000, "r", "p", "z", "a1", null, null);
        ReflectionTestUtils.setField(order, "id", 1L);
        lenient().when(orderItemRepository.findById(100L)).thenReturn(Optional.of(item));
        lenient().when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        lenient().when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> {
            Claim claim = inv.getArgument(0);
            ReflectionTestUtils.setField(claim, "id", 7L);
            ReflectionTestUtils.setField(claim, "createdAt", LocalDateTime.now());
            return claim;
        });
    }

    private void setItemStatus(OrderItemStatus status) {
        ReflectionTestUtils.setField(item, "status", status);
    }

    @Test
    @DisplayName("O-5 취소 신청 — ORDERED에서만 허용, 접수 시 CANCEL_REQUESTED 조건부 전이")
    void requestCancel() {
        setItemStatus(OrderItemStatus.ORDERED);
        when(statusChanger.requestClaim(eq(100L), eq(OrderItemStatus.ORDERED),
                eq(OrderItemStatus.CANCEL_REQUESTED), any())).thenReturn(true);

        ClaimResponse response = claimService.request(1L, 100L, new ClaimRequest(ClaimType.CANCEL, "단순 변심"));

        assertThat(response.status()).isEqualTo("REQUESTED");
        assertThat(response.type()).isEqualTo("CANCEL");
    }

    @Test
    @DisplayName("O-5 — 01 §3 매트릭스 위반은 CLAIM_NOT_ALLOWED (SHIPPING 취소, ORDERED 반품, CONFIRMED 반품)")
    void matrixViolations() {
        setItemStatus(OrderItemStatus.SHIPPING);
        assertClaimNotAllowed(ClaimType.CANCEL);
        setItemStatus(OrderItemStatus.ORDERED);
        assertClaimNotAllowed(ClaimType.RETURN);
        setItemStatus(OrderItemStatus.CONFIRMED);
        assertClaimNotAllowed(ClaimType.RETURN);
        setItemStatus(OrderItemStatus.PENDING);
        assertClaimNotAllowed(ClaimType.CANCEL);
    }

    private void assertClaimNotAllowed(ClaimType type) {
        assertThatThrownBy(() -> claimService.request(1L, 100L, new ClaimRequest(type, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CLAIM_NOT_ALLOWED);
    }

    @Test
    @DisplayName("O-5 — 활성 클레임 존재 시 409 CLAIM_ALREADY_REQUESTED")
    void duplicateRequest() {
        setItemStatus(OrderItemStatus.DELIVERED);
        when(claimRepository.existsByOrderItemIdAndStatus(100L, ClaimStatus.REQUESTED)).thenReturn(true);

        assertThatThrownBy(() -> claimService.request(1L, 100L, new ClaimRequest(ClaimType.RETURN, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CLAIM_ALREADY_REQUESTED);
    }

    @Test
    @DisplayName("O-5 — 조건부 UPDATE 0건(경합 패배) 시 CLAIM_NOT_ALLOWED")
    void raceLost() {
        setItemStatus(OrderItemStatus.DELIVERED);
        when(statusChanger.requestClaim(eq(100L), eq(OrderItemStatus.DELIVERED),
                eq(OrderItemStatus.RETURN_REQUESTED), any())).thenReturn(false);

        assertThatThrownBy(() -> claimService.request(1L, 100L, new ClaimRequest(ClaimType.RETURN, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CLAIM_NOT_ALLOWED);
    }

    @Test
    @DisplayName("O-5 — 남의 주문 아이템은 ORDER_ITEM_NOT_FOUND")
    void foreignItem() {
        setItemStatus(OrderItemStatus.ORDERED);
        assertThatThrownBy(() -> claimService.request(99L, 100L, new ClaimRequest(ClaimType.CANCEL, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_ITEM_NOT_FOUND);
    }
}
