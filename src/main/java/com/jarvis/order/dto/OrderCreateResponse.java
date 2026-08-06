package com.jarvis.order.dto;

import com.jarvis.global.response.StringId;
import com.jarvis.order.Order;

/**
 * O-1·O-2 응답 (04 §4) — status로 결제 성공(PAID)/실패(PAYMENT_FAILED) 판별.
 *
 * <p>실패 원인은 failureReason으로 가른다(2026-08-05 FE 요청). 재고 부족은 재결제(O-2)가 절대
 * 성공하지 않아 카드 거절과 복구 동선이 다른데, 이 필드가 없으면 FE가 둘을 구분할 근거가 없어
 * 재결제 유도 → 재실패가 무한 반복된다. 값은 order_status_logs.reason에 쓰던 것과 같다.
 */
public record OrderCreateResponse(@StringId Long orderId, String orderNo, String status,
                                  String failureReason) {

    public static OrderCreateResponse from(Order order, String failureReason) {
        return new OrderCreateResponse(order.getId(), order.orderNo(), order.getStatus().name(), failureReason);
    }
}
