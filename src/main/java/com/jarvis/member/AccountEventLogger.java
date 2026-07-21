package com.jarvis.member;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * account_event_logs 적재 (02 D32 · 03 §3-1 — AuthService 성공/실패 지점에서 직접).
 * REQUIRES_NEW: 로그인 실패처럼 본 트랜잭션이 예외로 롤백돼도 로그는 남아야 한다.
 * @Async: 호출 스레드가 auth 트랜잭션의 커넥션을 쥔 채 두 번째 커넥션을 기다리면
 * 로그인 몰림 시 풀 전체가 상호 대기로 고갈된다(풀 10 기준 동시 10건이면 데드락) —
 * 별도 스레드의 독립 트랜잭션으로 분리해 요청 스레드는 커넥션을 1개만 쓴다.
 * 이벤트 로그는 best-effort(02 D32) — 적재 실패가 인증 자체를 실패시키지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AccountEventLogger {

    private final AccountEventLogRepository repository;

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(Long memberId, AccountEventType eventType, String ipAddress) {
        repository.save(AccountEventLog.of(memberId, eventType, ipAddress));
    }
}
