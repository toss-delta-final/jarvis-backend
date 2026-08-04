package com.jarvis.global.ratelimit;

import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 로그인·회원가입 시도 제한 (07 §3-4) — 목적은 정밀 과금이 아니라 <b>무차별 대입 차단</b>이다.
 *
 * <p><b>IP와 계정 두 축을 같이 센다.</b> 한 축만 세면 각각 구멍이 있다 —
 * IP만 세면 공격자가 IP를 바꿔가며 분산하면 뚫리고, 계정만 세면 공격자가 남의 계정에 일부러
 * 실패를 쌓아 <b>잠가버리는 DoS</b>가 된다. 그래서 계정 잠금도 하지 않는다 — 초과해도 창이 지나면
 * 자동으로 풀린다.
 *
 * <p>알고리즘은 <b>고정 창</b>이다. 창 경계에서 한도의 2배까지 통과할 수 있지만 남용 차단에는
 * 충분하고, 슬라이딩 로그처럼 요청 시각을 전부 저장하지 않아 메모리가 일정하다.
 *
 * <p>{@code INCR}과 {@code EXPIRE}를 <b>Lua로 원자화</b>한다. 둘로 나누면 첫 요청 직후 프로세스가
 * 죽었을 때 TTL이 없는 키가 남아 <b>영원히 카운트가 쌓인다</b> — 그 계정은 영구 차단된다.
 *
 * <p>Redis 장애 시 <b>fail-open</b> — 못 세면 통과시킨다. "잠깐 방어가 없는 것"이
 * "전원 로그인 불가"보다 낫다.
 */
@Slf4j
@Component
public class LoginRateLimiter {

    /** 카운터를 올리고 첫 요청에만 TTL을 건다. 반환: {현재 카운트, 남은 TTL(ms)} */
    @SuppressWarnings("rawtypes") // Redis Lua는 배열을 List로 돌려주는데 스크립트 타입은 raw만 받는다
    private static final RedisScript<List> INCR_WITH_TTL = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return {count, redis.call('PTTL', KEYS[1])}
            """, List.class);

    private static final String IP_KEY = "ratelimit:login:ip:";
    private static final String ACCOUNT_KEY = "ratelimit:login:acct:";

    private final StringRedisTemplate redisTemplate;
    private final Duration window;
    private final int accountLimit;
    private final int ipLimit;

    public LoginRateLimiter(StringRedisTemplate redisTemplate,
                            @Value("${app.rate-limit.login.window-minutes}") long windowMinutes,
                            @Value("${app.rate-limit.login.account-limit}") int accountLimit,
                            @Value("${app.rate-limit.login.ip-limit}") int ipLimit) {
        this.redisTemplate = redisTemplate;
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
        Attempt ip = hit(IP_KEY + clientIp);
        Attempt acct = account == null ? Attempt.SKIPPED : hit(ACCOUNT_KEY + account.toLowerCase());

        boolean exceeded = ip.count() > ipLimit || acct.count() > accountLimit;
        if (!exceeded) {
            return;
        }
        long retryAfterSeconds = Math.max(1, Math.max(ip.ttlMillis(), acct.ttlMillis()) / 1000);
        log.warn("로그인 시도 제한 — ip={} ipCount={} acctCount={} retryAfter={}s",
                clientIp, ip.count(), acct.count(), retryAfterSeconds);
        throw new RateLimitedException(retryAfterSeconds);
    }

    private Attempt hit(String key) {
        try {
            List<?> result = redisTemplate.execute(INCR_WITH_TTL, List.of(key),
                    String.valueOf(window.toMillis()));
            if (result == null || result.size() < 2) {
                return Attempt.SKIPPED;
            }
            return new Attempt(((Number) result.get(0)).longValue(),
                    ((Number) result.get(1)).longValue());
        } catch (RuntimeException e) {
            // fail-open — 못 세면 통과시킨다. 방어가 잠깐 없는 게 전원 로그인 불가보다 낫다
            log.warn("로그인 시도 집계 실패 — 제한 없이 통과. key={}", key, e);
            return Attempt.SKIPPED;
        }
    }

    /** 세지 못한 경우(계정 없음·Redis 장애)는 한도 판정에서 제외되도록 count 0 */
    private record Attempt(long count, long ttlMillis) {
        static final Attempt SKIPPED = new Attempt(0, 0);
    }

    /** {@code Retry-After} 헤더를 실으려고 예외에 남은 시간을 담는다 */
    public static class RateLimitedException extends BusinessException {

        private final long retryAfterSeconds;

        RateLimitedException(long retryAfterSeconds) {
            super(ErrorCode.RATE_LIMITED);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
