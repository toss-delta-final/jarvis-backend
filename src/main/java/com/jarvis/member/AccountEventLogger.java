package com.jarvis.member;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * account_event_logs 적재 (02 D32 · 03 §3-1 — AuthService 성공/실패 지점에서 직접).
 * REQUIRES_NEW: 로그인 실패처럼 본 트랜잭션이 예외로 롤백돼도 로그는 남아야 한다.
 */
@Component
@RequiredArgsConstructor
public class AccountEventLogger {

    private final AccountEventLogRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(Long memberId, AccountEventType eventType, String ipAddress) {
        repository.save(AccountEventLog.of(memberId, eventType, ipAddress));
    }
}
