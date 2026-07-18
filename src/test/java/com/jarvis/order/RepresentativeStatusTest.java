package com.jarvis.order;

import static com.jarvis.order.OrderItemStatus.CANCELLED;
import static com.jarvis.order.OrderItemStatus.CANCEL_REQUESTED;
import static com.jarvis.order.OrderItemStatus.CONFIRMED;
import static com.jarvis.order.OrderItemStatus.DELIVERED;
import static com.jarvis.order.OrderItemStatus.ORDERED;
import static com.jarvis.order.OrderItemStatus.RETURNED;
import static com.jarvis.order.OrderItemStatus.RETURN_REQUESTED;
import static com.jarvis.order.OrderItemStatus.SHIPPING;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 01 §4 — 대표 상태 8규칙, 위에서부터 첫 매칭 */
class RepresentativeStatusTest {

    @Test
    @DisplayName("주문 PENDING/PAYMENT_FAILED가 아이템보다 우선한다")
    void orderStatusFirst() {
        assertThat(RepresentativeStatus.of(OrderStatus.PENDING, List.of(OrderItemStatus.PENDING)))
                .isEqualTo(RepresentativeStatus.PENDING);
        assertThat(RepresentativeStatus.of(OrderStatus.PAYMENT_FAILED, List.of(OrderItemStatus.PENDING)))
                .isEqualTo(RepresentativeStatus.PAYMENT_FAILED);
    }

    @Test
    @DisplayName("*_REQUESTED 존재 시 CLAIM_IN_PROGRESS")
    void claimInProgress() {
        assertThat(RepresentativeStatus.of(OrderStatus.PAID, List.of(DELIVERED, RETURN_REQUESTED)))
                .isEqualTo(RepresentativeStatus.CLAIM_IN_PROGRESS);
        assertThat(RepresentativeStatus.of(OrderStatus.PAID, List.of(ORDERED, CANCEL_REQUESTED)))
                .isEqualTo(RepresentativeStatus.CLAIM_IN_PROGRESS);
    }

    @Test
    @DisplayName("전부 CONFIRMED → CONFIRMED, 종결 혼합 → COMPLETED")
    void terminalStates() {
        assertThat(RepresentativeStatus.of(OrderStatus.PAID, List.of(CONFIRMED, CONFIRMED)))
                .isEqualTo(RepresentativeStatus.CONFIRMED);
        assertThat(RepresentativeStatus.of(OrderStatus.PAID, List.of(CONFIRMED, RETURNED)))
                .isEqualTo(RepresentativeStatus.COMPLETED);
        assertThat(RepresentativeStatus.of(OrderStatus.CANCELLED, List.of(CANCELLED, CANCELLED)))
                .isEqualTo(RepresentativeStatus.COMPLETED);
    }

    @Test
    @DisplayName("진행 중 우선순위 — ORDERED > SHIPPING > DELIVERED")
    void inProgressPriority() {
        assertThat(RepresentativeStatus.of(OrderStatus.PAID, List.of(ORDERED, SHIPPING, DELIVERED)))
                .isEqualTo(RepresentativeStatus.ORDERED);
        assertThat(RepresentativeStatus.of(OrderStatus.PAID, List.of(SHIPPING, DELIVERED)))
                .isEqualTo(RepresentativeStatus.SHIPPING);
        assertThat(RepresentativeStatus.of(OrderStatus.PAID, List.of(DELIVERED, RETURNED)))
                .isEqualTo(RepresentativeStatus.DELIVERED);
    }
}
