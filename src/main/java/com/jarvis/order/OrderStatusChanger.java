package com.jarvis.order;

import com.jarvis.product.ProductStockService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * 아이템 상태 전이는 아이템마다 1행(orderItemId 필수), 주문 상태 전이만 null — 해상도는 "무엇의 상태를
 * 바꿨나"로 가른다(2026-08-06 개정, 구 "배치는 주문 단위 1행" 폐기).
 * 취소·반품 완료 actor는 승인 주체가 아니라 신청 주체(USER).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusChanger {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ClaimRepository claimRepository;
    private final OrderStatusLogRepository logRepository;
    private final ProductStockService productStockService;

    /** O-1 주문 생성 — NULL → PENDING (SYSTEM) */
    public void logOrderCreated(Order order) {
        logRepository.save(OrderStatusLog.ofOrder(order.getId(), null,
                OrderStatus.PENDING.name(), ActorType.SYSTEM, null));
    }

    /** 모의 결제 실패 — reason에 실패 코드 (01 §6.5) */
    public void paymentFailed(Order order, String failureCode) {
        String from = order.getStatus().name();
        order.markPaymentFailed();
        logRepository.save(OrderStatusLog.ofOrder(order.getId(), from,
                OrderStatus.PAYMENT_FAILED.name(), ActorType.SYSTEM, failureCode));
    }

    /** 결제 성공 — Order PAID + 아이템 전량 ORDERED 같은 트랜잭션 (01 D9). 아이템 ORDERED 로그는 없음 */
    public void paymentSucceeded(Order order, List<OrderItem> items, LocalDateTime now) {
        String from = order.getStatus().name();
        order.markPaid(now);
        items.forEach(item -> item.markOrdered(now));
        logRepository.save(OrderStatusLog.ofOrder(order.getId(), from,
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
     * 배송 mock 전이 1단계 (01 §6) — 조건부 UPDATE(+status_changed_at 갱신) 후 아이템마다 로그 1행.
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
        int count = 0;
        for (OrderItem item : candidates) {
            if (orderItemRepository.transitionStatus(item.getId(), from, to, now) == 1) {
                if (writeLog) {
                    // 아이템 상태 전이라 아이템마다 1행 (01 §6.5 규칙 4) — 구 주문 단위 묶음은 폐기
                    logRepository.save(OrderStatusLog.ofItem(item.getOrderId(), item.getId(),
                            from.name(), to.name(), ActorType.SYSTEM, null));
                }
                count++;
            }
        }
        return count;
    }

    /**
     * I-30 판매자 발송 (01 §2-3 — ORDERED→SHIPPING의 유일한 트리거, 2026-08-06 개정).
     * 조건부 UPDATE라 경합에 지면 0건이 돌아온다 — 이중 발송은 별도 락 없이 여기서 걸린다.
     * 성공하면 actor=SELLER로 아이템 단위 로그 1행을 남겨 I-14 분석에 합류시킨다.
     *
     * @return 전이했으면 true, 이미 다른 실행이 가져갔으면 false (호출부가 409로 번역)
     */
    @Transactional
    public boolean shipBySeller(OrderItem item, String reason, LocalDateTime now) {
        if (orderItemRepository.transitionStatus(
                item.getId(), OrderItemStatus.ORDERED, OrderItemStatus.SHIPPING, now) != 1) {
            return false;
        }
        logRepository.save(OrderStatusLog.ofItem(item.getOrderId(), item.getId(),
                OrderItemStatus.ORDERED.name(), OrderItemStatus.SHIPPING.name(),
                ActorType.SELLER, reason));
        return true;
    }

    /**
     * 클레임 자동 승인 배치 (01 D10·§6) — 아이템 종결 전이 + claim COMPLETED 같은 트랜잭션.
     * 로그는 아이템마다 1행, actor=USER(신청 주체), reason=claim.reason (01 §6.5 규칙 3·4).
     * 전량 취소 도달 시 orders.status → CANCELLED 승격 + 주문 단위 로그 1행 (01 §2-1).
     *
     * <p>확정분의 재고를 같은 트랜잭션에서 되돌린다(2026-08-10 — 구 "복원 MVP 미구현"). 신청
     * 시점이 아니라 이 승인 지점인 이유는, 신청은 반려될 수 있는 상태라 재고를 미리 풀면 팔 수 없는
     * 물건을 파는 셈이 되기 때문이다.
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
        List<Long> cancelledOrderIds = new ArrayList<>();
        // 취소·반품 모두 복원 대상이다. 같은 (상품, 옵션)이 여러 클레임에 걸리면 합산해 한 번만 친다
        Map<ProductStockService.StockKey, Integer> toRestore = new HashMap<>();
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
            // 아이템 상태 전이라 아이템마다 1행 — 구 (주문, from, to) 묶음은 폐기(01 §6.5 규칙 4).
            // 묶을 때 버려지던 아이템별 claim.reason도 이제 각 행에 그대로 남는다.
            logRepository.save(OrderStatusLog.ofItem(item.getOrderId(), item.getId(),
                    from.name(), to.name(), ActorType.USER, claim.getReason()));
            if (to == OrderItemStatus.CANCELLED) {
                cancelledOrderIds.add(item.getOrderId());
            }
            toRestore.merge(new ProductStockService.StockKey(item.getProductId(), item.getOptionId()),
                    item.getQuantity(), Integer::sum);
            approved++;
        }
        if (!toRestore.isEmpty()) {
            productStockService.restore(toRestore);
        }
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
                            logRepository.save(OrderStatusLog.ofOrder(orderId, from,
                                    OrderStatus.CANCELLED.name(), ActorType.USER, null));
                        });
            }
        });
    }
}
