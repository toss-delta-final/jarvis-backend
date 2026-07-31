package com.jarvis.chat;

/**
 * I-20 세션 종료 사유 (05 §2-1). 와이어 값은 노션 정본대로 camelCase — enum 상수명과 분리해
 * {@link #wireValue()}로 내보낸다. I-20은 "프로필을 지금 승격하라"는 신호이고, 승격 트리거 셋 중
 * Spring이 감지할 수 있는 건 로그아웃 하나뿐이다(노션 I-20 정본 2026-07-31 확정) — 유휴는 FastAPI
 * 내부 idle flush, "새 대화"는 FastAPI가 /chat의 threadId 최초 등장으로 자체 감지하며(Spring은
 * thread를 모른다), 탭 종료는 계약에서 제외됐다.
 */
public enum SessionEndReason {
    LOGOUT("logout");

    private final String wireValue;

    SessionEndReason(String wireValue) {
        this.wireValue = wireValue;
    }

    /** I-20 body의 reason 필드 값 (노션 정본 알려진 값: logout|inactivityTimeout; Spring은 logout만 발화) */
    public String wireValue() {
        return wireValue;
    }
}
