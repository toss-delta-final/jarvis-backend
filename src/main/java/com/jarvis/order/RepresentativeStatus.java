package com.jarvis.order;

import java.util.Collection;

/**
 * 주문 대표 상태 (01 §4) — DB 저장 금지, 아이템 상태에서 계산.
 * enum 코드로 응답하고 표시 문구는 FE 매핑 (2026-07-17 FE 요청).
 */
public enum RepresentativeStatus {
    PENDING, PAYMENT_FAILED, CLAIM_IN_PROGRESS, CONFIRMED, COMPLETED, ORDERED, SHIPPING, DELIVERED;

    /** 01 §4 규칙 — 위에서부터 첫 매칭 */
    public static RepresentativeStatus of(OrderStatus orderStatus, Collection<OrderItemStatus> itemStatuses) {
        if (orderStatus == OrderStatus.PENDING) {
            return PENDING;
        }
        if (orderStatus == OrderStatus.PAYMENT_FAILED) {
            return PAYMENT_FAILED;
        }
        if (itemStatuses.stream().anyMatch(OrderItemStatus::isClaimRequested)) {
            return CLAIM_IN_PROGRESS;
        }
        if (itemStatuses.stream().allMatch(s -> s == OrderItemStatus.CONFIRMED)) {
            return CONFIRMED;
        }
        if (itemStatuses.stream().allMatch(OrderItemStatus::isFinal)) {
            return COMPLETED;
        }
        if (itemStatuses.stream().anyMatch(s -> s == OrderItemStatus.ORDERED)) {
            return ORDERED;
        }
        if (itemStatuses.stream().anyMatch(s -> s == OrderItemStatus.SHIPPING)) {
            return SHIPPING;
        }
        return DELIVERED;
    }
}
