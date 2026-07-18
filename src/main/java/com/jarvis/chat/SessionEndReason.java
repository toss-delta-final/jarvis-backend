package com.jarvis.chat;

/**
 * I-20 세션 종료 사유 (05 §2-1). IDLE_TIMEOUT은 Redis TTL 자연 소멸이라 BE가 통지하지 않는다
 * — FastAPI 자체 TTL이 백스톱(05 §3). TAB_CLOSE는 FE 소관(비콘 불가 시 미전송 감수).
 */
public enum SessionEndReason {
    LOGOUT, IDLE_TIMEOUT, NEW_CONVERSATION, TAB_CLOSE
}
