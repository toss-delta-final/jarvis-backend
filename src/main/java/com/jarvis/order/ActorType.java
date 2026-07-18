package com.jarvis.order;

/**
 * order_status_logs.actor_type (02 §3) — 신청 주체 기준: 자동 승인 스케줄러가 실행해도
 * 취소·반품 완료는 USER (01 §6.5 규칙 3).
 */
public enum ActorType {
    USER, SELLER, ADMIN, SYSTEM
}
