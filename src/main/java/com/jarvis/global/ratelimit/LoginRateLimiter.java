package com.jarvis.global.ratelimit;

import com.jarvis.global.ratelimit.FixedWindowCounter.Attempt;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 로그인·회원가입 시도 제한 (07 §3-4) — 목적은 정밀 과금이 아니라 <b>무차별 대입 차단</b>이다.
 *
 * <p><b>IP와 계정 두 축을 같이 센다.</b> 한 축만 세면 각각 구멍이 있다 —
 * IP만 세면 공격자가 IP를 바꿔가며 분산하면 뚫리고, 계정만 세면 공격자가 남의 계정에 일부러
 * 실패를 쌓아 <b>잠가버리는 DoS</b>가 된다. 그래서 계정 잠금도 하지 않는다 — 초과해도 창이 지나면
 * 자동으로 풀린다.
 *
 * <p>고정 창·원자적 집계·fail-open은 {@link FixedWindowCounter} 소관이다.
 */
@Slf4j
@Component
public class LoginRateLimiter {

    private static final String IP_KEY = "ratelimit:login:ip:";
    private static final String ACCOUNT_KEY = "ratelimit:login:acct:";

    private final FixedWindowCounter counter;
    private final Duration window;
    private final int accountLimit;
    private final int ipLimit;

    public LoginRateLimiter(FixedWindowCounter counter,
                            @Value("${app.rate-limit.login.window-minutes}") long windowMinutes,
                            @Value("${app.rate-limit.login.account-limit}") int accountLimit,
                            @Value("${app.rate-limit.login.ip-limit}") int ipLimit) {
        this.counter = counter;
        this.window = Duration.ofMinutes(windowMinutes);
        this.accountLimit = accountLimit;
        this.ipLimit = ipLimit;
    }

    /**
     * 시도를 한 번 세고, 어느 축이든 한도를 넘었으면 429로 막는다.
     *
     * <p>초과를 발견해도 <b>두 축을 모두 센다</b> — 한쪽에서 조기 반환하면 다른 축의 카운트가
     * 누락돼, 공격자가 한 축만 채워두고 다른 축을 자유롭게 쓸 수 있다.
     */
    public void check(String clientIp, String account) {
        Attempt ip = counter.hit(IP_KEY + clientIp, window);
        Attempt acct = account == null
                ? Attempt.SKIPPED
                : counter.hit(ACCOUNT_KEY + account.toLowerCase(), window);

        boolean exceeded = ip.count() > ipLimit || acct.count() > accountLimit;
        if (!exceeded) {
            return;
        }
        long retryAfterSeconds = Math.max(1, Math.max(ip.ttlMillis(), acct.ttlMillis()) / 1000);
        log.warn("로그인 시도 제한 — ip={} ipCount={} acctCount={} retryAfter={}s",
                clientIp, ip.count(), acct.count(), retryAfterSeconds);
        throw new RateLimitedException(retryAfterSeconds);
    }
}
