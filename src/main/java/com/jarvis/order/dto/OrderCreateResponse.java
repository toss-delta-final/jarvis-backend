package com.jarvis.order.dto;

import com.jarvis.order.Order;

/** O-1·O-2 응답 (04 §4) — status로 결제 성공(PAID)/실패(PAYMENT_FAILED) 판별, 실패 시 FE가 재시도 유도 */
public record OrderCreateResponse(Long orderId, String orderNo, String status) {

    public static OrderCreateResponse from(Order order) {
        return new OrderCreateResponse(order.getId(), order.orderNo(), order.getStatus().name());
    }
}
