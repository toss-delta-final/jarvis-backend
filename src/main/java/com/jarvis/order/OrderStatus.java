package com.jarvis.order;

/** 결제 수준 상태 (01 §2-1) — CANCELLED는 전량 취소 시 승격 전용 (02 D32) */
public enum OrderStatus {
    PENDING, PAID, PAYMENT_FAILED, CANCELLED
}
