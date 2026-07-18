package com.jarvis.order;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * mock 배송 전이 + 클레임 자동 승인 (01 §6, D4·D10) — 1분 틱.
 * 2층 방어: 조건부 UPDATE(정합성 최종 방어선) + Redis 분산 락(틱당 1대 — 03 D-분산5).
 * 같은 틱 안에서 연쇄 전이 없음 — 전이 시 status_changed_at이 NOW()로 갱신되므로 다음 단계 임계값에 미달.
 */
@Component
@RequiredArgsConstructor
public class OrderMockScheduler {

    private final OrderStatusChanger statusChanger;
    private final MockProperties mockProperties;

    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "order-shipment-transition", lockAtMostFor = "PT50S")
    public void transitionShipment() {
        LocalDateTime now = LocalDateTime.now();
        statusChanger.transitionShipment(OrderItemStatus.ORDERED, OrderItemStatus.SHIPPING,
                now.minusMinutes(mockProperties.shippingMinutes()), true);
        statusChanger.transitionShipment(OrderItemStatus.SHIPPING, OrderItemStatus.DELIVERED,
                now.minusMinutes(mockProperties.deliveryMinutes()), true);
        // CONFIRMED 전이는 로그 미기록 (01 §6.5 규칙 2). 반품 신청 중(*_REQUESTED)은 WHERE에서 자연 제외 (01 D8)
        statusChanger.transitionShipment(OrderItemStatus.DELIVERED, OrderItemStatus.CONFIRMED,
                now.minusMinutes(mockProperties.confirmMinutes()), false);
    }

    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "order-claim-auto-approve", lockAtMostFor = "PT50S")
    public void approveClaims() {
        statusChanger.approveDueClaims(LocalDateTime.now().minusMinutes(mockProperties.claimApproveMinutes()));
    }
}
