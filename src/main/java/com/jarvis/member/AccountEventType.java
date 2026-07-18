package com.jarvis.member;

/** account_event_logs.event_type — DB ENUM과 1:1 (02 D32) */
public enum AccountEventType {
    SIGNUP, LOGIN_SUCCESS, LOGIN_FAIL, LOGOUT, WITHDRAW
}
