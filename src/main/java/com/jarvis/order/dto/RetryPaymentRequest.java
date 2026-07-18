package com.jarvis.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** O-2 (04 §4) — 실패 주문 재결제, 결제 수단 교체 가능 */
public record RetryPaymentRequest(
        @NotBlank(message = "결제 수단은 필수입니다.")
        @Size(max = 30, message = "결제 수단이 올바르지 않습니다.") String paymentMethod) {
}
