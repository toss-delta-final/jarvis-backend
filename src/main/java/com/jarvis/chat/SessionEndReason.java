package com.jarvis.chat;

/**
 * I-20 세션 종료 사유 (05 §2-1). 와이어 값은 노션 정본대로 camelCase — enum 상수명과 분리해
 * {@link #wireValue()}로 내보낸다. IDLE_TIMEOUT은 Redis TTL 자연 소멸이라 BE가 통지하지 않는다
 * — FastAPI 자체 TTL이 백스톱(05 §3). TAB_CLOSE는 FE 소관(비콘 불가 시 미전송 감수).
 */
public enum SessionEndReason {
    LOGOUT("logout"),
    IDLE_TIMEOUT("inactivityTimeout"),
    NEW_CONVERSATION("newConversation"),
    TAB_CLOSE("tabClose");

    private final String wireValue;

    SessionEndReason(String wireValue) {
        this.wireValue = wireValue;
    }

    /** I-20 body의 reason 필드 값 (노션 정본: logout|tabClose|inactivityTimeout|newConversation) */
    public String wireValue() {
        return wireValue;
    }
}
