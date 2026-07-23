package com.jarvis.chat;

/**
 * I-20 세션 종료 사유 (05 §2-1). 와이어 값은 노션 정본대로 camelCase — enum 상수명과 분리해
 * {@link #wireValue()}로 내보낸다. 회원이 로그아웃하거나(logout) 새 대화를 시작할 때(newConversation)만
 * Spring이 발화한다 — 유휴 종료는 FastAPI 내부 idle flush로, 탭 종료는 계약에서 제외됐다(노션 I-20 정본).
 */
public enum SessionEndReason {
    LOGOUT("logout"),
    NEW_CONVERSATION("newConversation");

    private final String wireValue;

    SessionEndReason(String wireValue) {
        this.wireValue = wireValue;
    }

    /** I-20 body의 reason 필드 값 (노션 정본 알려진 값: logout|inactivityTimeout|newConversation; Spring은 logout·newConversation만 발화) */
    public String wireValue() {
        return wireValue;
    }
}
