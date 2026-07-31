package com.jarvis.chat;

/**
 * I-20 세션 종료 사유 (05 §2-1). 와이어 값은 노션 정본대로 camelCase — enum 상수명과 분리해
 * {@link #wireValue()}로 내보낸다. Spring이 발화하는 사유는 로그아웃 하나뿐이다(노션 I-20 정본
 * 2026-07-30 확정) — 유휴 종료는 FastAPI 내부 idle flush로, 탭 종료는 계약에서 제외됐고,
 * "새 대화"는 FE가 threadId만 새로 만들어 세션을 유지하므로 사유 자체가 사라졌다(SPEC-CHAT-SESSION).
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
