package com.jarvis.order;

/** 이행+클레임 수준 상태 9종 (01 §2-2, D9·D8·D11) — 전이는 01 §2-3 표가 전부 */
public enum OrderItemStatus {
    PENDING, ORDERED, SHIPPING, DELIVERED, CONFIRMED,
    CANCEL_REQUESTED, CANCELLED, RETURN_REQUESTED, RETURNED;

    /** 01 §3 액션 매트릭스 — 서버가 최종 검증 (프론트가 숨겨도 재검증) */
    public boolean canCancel() {
        return this == ORDERED;
    }

    public boolean canReturn() {
        return this == DELIVERED;
    }

    public boolean canReview() {
        return this == DELIVERED || this == CONFIRMED;
    }

    public boolean isClaimRequested() {
        return this == CANCEL_REQUESTED || this == RETURN_REQUESTED;
    }

    /** 종결 상태 (01 §2-2) */
    public boolean isFinal() {
        return this == CONFIRMED || this == CANCELLED || this == RETURNED;
    }
}
