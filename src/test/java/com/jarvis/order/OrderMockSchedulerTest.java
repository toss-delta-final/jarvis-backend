package com.jarvis.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * mock 배송 스케줄러 (01 §6) — 2026-08-06 개정으로 ORDERED→SHIPPING이 빠졌다.
 * 그 전이는 판매자 발송(I-30) 소관이며, 여기에 되살아나면 HITL 승인 왕복 도중 스케줄러가
 * 먼저 전이시켜 409가 기본 경로가 된다(그래서 애초에 걷어낸 것이다).
 */
@ExtendWith(MockitoExtension.class)
class OrderMockSchedulerTest {

    @Mock private OrderStatusChanger statusChanger;

    @Test
    @DisplayName("ORDERED→SHIPPING은 더 이상 스케줄러가 일으키지 않는다 (01 D4 개정)")
    void doesNotAutoShip() {
        OrderMockScheduler scheduler = new OrderMockScheduler(statusChanger,
                new MockProperties(5, 10, 5));

        scheduler.transitionShipment();

        verify(statusChanger, never()).transitionShipment(
                eq(OrderItemStatus.ORDERED), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("SHIPPING→DELIVERED는 로그를 남기고, DELIVERED→CONFIRMED는 남기지 않는다 (01 §6.5 규칙 2)")
    void keepsRemainingTransitions() {
        OrderMockScheduler scheduler = new OrderMockScheduler(statusChanger,
                new MockProperties(5, 10, 5));

        scheduler.transitionShipment();

        verify(statusChanger).transitionShipment(
                eq(OrderItemStatus.SHIPPING), eq(OrderItemStatus.DELIVERED), any(), eq(true));
        verify(statusChanger).transitionShipment(
                eq(OrderItemStatus.DELIVERED), eq(OrderItemStatus.CONFIRMED), any(), eq(false));
    }
}
