package com.jarvis.order;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모든 주문/아이템 상태 전이의 단일 관문 (01 D12) — 상태 UPDATE와 order_status_logs INSERT를
 * 같은 트랜잭션으로 묶어 로그 누락을 구조적으로 차단한다. 이 클래스를 우회한 상태 변경은 리뷰에서 거부된다.
 *
 * 기록 규칙 (01 §6.5): *_REQUESTED(claim이 정본)·CONFIRMED(분석 미사용)·아이템 ORDERED(PAID와 동시) 미기록.
 * 같은 주문의 동일 전이 배치는 주문 단위 1행. 취소·반품 완료 actor는 승인 주체가 아니라 신청 주체(USER).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusChanger {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ClaimRepository claimRepository;
    private final OrderStatusLogRepository logRepository;

    /** O-1 주문 생성 — NULL → PENDING (SYSTEM) */
    public void logOrderCreated(Order order) {
        logRepository.save(OrderStatusLog.of(order.getId(), null,
                OrderStatus.PENDING.name(), ActorType.SYSTEM, null));
    }

    /** 모의 결제 실패 — reason에 실패 코드 (01 §6.5) */
    public void paymentFailed(Order order, String failureCode) {
        String from = order.getStatus().name();
        order.markPaymentFailed();
        logRepository.save(OrderStatusLog.of(order.getId(), from,
                OrderStatus.PAYMENT_FAILED.name(), ActorType.SYSTEM, failureCode));
    }

    /** 결제 성공 — Order PAID + 아이템 전량 ORDERED 같은 트랜잭션 (01 D9). 아이템 ORDERED 로그는 없음 */
    public void paymentSucceeded(Order order, List<OrderItem> items, LocalDateTime now) {
        String from = order.getStatus().name();
        order.markPaid(now);
        items.forEach(item -> item.markOrdered(now));
        logRepository.save(OrderStatusLog.of(order.getId(), from,
                OrderStatus.PAID.name(), ActorType.SYSTEM, null));
    }

    /**
     * 클레임 신청 접수 — 조건부 UPDATE로 동시 신청 중 한쪽만 성공 (01 §5).
     * *_REQUESTED는 로그 미기록 — 신청의 정본은 claim 테이블 (01 §6.5 규칙 1).
     */
    public boolean requestClaim(Long orderItemId, OrderItemStatus from, OrderItemStatus to, LocalDateTime now) {
        return orderItemRepository.transitionStatus(orderItemId, from, to, now) == 1;
    }

    /**
     * 배송 mock 전이 1단계 (01 §6) — 조건부 UPDATE(+status_changed_at 갱신) 후 주문 단위 로그.
     * CONFIRMED 전이는 writeLog=false로 호출 (01 §6.5 규칙 2).
     */
    @Transactional
    public int transitionShipment(OrderItemStatus from, OrderItemStatus to,
                                  LocalDateTime threshold, boolean writeLog) {
        List<OrderItem> candidates =
                orderItemRepository.findAllByStatusAndStatusChangedAtLessThanEqual(from, threshold);
        if (candidates.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        Set<Long> transitionedOrders = new LinkedHashSet<>();
        int count = 0;
        for (OrderItem item : candidates) {
            if (orderItemRepository.transitionStatus(item.getId(), from, to, now) == 1) {
                transitionedOrders.add(item.getOrderId());
                count++;
            }
        }
        if (writeLog) {
            transitionedOrders.forEach(orderId -> logRepository.save(
                    OrderStatusLog.of(orderId, from.name(), to.name(), ActorType.SYSTEM, null)));
        }
        return count;
    }

    /**
     * 클레임 자동 승인 배치 (01 D10·§6) — 아이템 종결 전이 + claim COMPLETED 같은 트랜잭션.
     * 로그는 (주문, 전이)당 1행, actor=USER(신청 주체), reason=claim.reason (01 §6.5 규칙 3·4).
     * 전량 취소 도달 시 orders.status → CANCELLED 승격 + 주문 단위 로그 1행 (01 §2-1).
     */
    @Transactional
    public int approveDueClaims(LocalDateTime threshold) {
        List<Claim> due = claimRepository.findAllByStatusAndCreatedAtLessThanEqual(ClaimStatus.REQUESTED, threshold);
        if (due.isEmpty()) {
            return 0;
        }
        Map<Long, OrderItem> items = orderItemRepository
                .findAllById(due.stream().map(Claim::getOrderItemId).toList())
                .stream().collect(Collectors.toMap(OrderItem::getId, Function.identity()));

        LocalDateTime now = LocalDateTime.now();
        Map<LogKey, String> logGroups = new LinkedHashMap<>();
        List<Long> cancelledOrderIds = new ArrayList<>();
        int approved = 0;
        for (Claim claim : due) {
            OrderItem item = items.get(claim.getOrderItemId());
            if (item == null) {
                log.warn("클레임 {} 대상 아이템 {} 없음 — 건너뜀", claim.getId(), claim.getOrderItemId());
                continue;
            }
            OrderItemStatus from = claim.requestedItemStatus();
            OrderItemStatus to = claim.completedItemStatus();
            if (orderItemRepository.transitionStatus(item.getId(), from, to, now) != 1) {
                continue;
            }
            claim.approve(now);
            logGroups.putIfAbsent(new LogKey(item.getOrderId(), from, to), claim.getReason());
            if (to == OrderItemStatus.CANCELLED) {
                cancelledOrderIds.add(item.getOrderId());
            }
            approved++;
        }
        logGroups.forEach((key, reason) -> logRepository.save(
                OrderStatusLog.of(key.orderId(), key.from().name(), key.to().name(), ActorType.USER, reason)));
        promoteFullyCancelledOrders(cancelledOrderIds);
        return approved;
    }

    private void promoteFullyCancelledOrders(List<Long> orderIds) {
        orderIds.stream().distinct().forEach(orderId -> {
            if (orderItemRepository.countByOrderIdAndStatusNot(orderId, OrderItemStatus.CANCELLED) == 0) {
                orderRepository.findById(orderId)
                        .filter(order -> order.getStatus() != OrderStatus.CANCELLED)
                        .ifPresent(order -> {
                            String from = order.getStatus().name();
                            order.markCancelled();
                            logRepository.save(OrderStatusLog.of(orderId, from,
                                    OrderStatus.CANCELLED.name(), ActorType.USER, null));
                        });
            }
        });
    }

    private record LogKey(Long orderId, OrderItemStatus from, OrderItemStatus to) {
    }
}
