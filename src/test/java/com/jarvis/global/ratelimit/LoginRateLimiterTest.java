package com.jarvis.global.ratelimit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** 로그인·가입 시도 제한 (07 §3-4) — IP·계정 두 축, 고정 창, fail-open */
@SuppressWarnings("unchecked") // RedisScript는 raw 타입이라 스텁·검증에서 캐스팅 경고가 불가피하다
@ExtendWith(MockitoExtension.class)
class LoginRateLimiterTest {

    private static final String IP = "203.0.113.9";
    private static final String EMAIL = "user@test.com";
    private static final String IP_KEY = "ratelimit:login:ip:" + IP;
    private static final String ACCT_KEY = "ratelimit:login:acct:" + EMAIL;

    @Mock StringRedisTemplate redisTemplate;

    /** 창 10분 / 계정 5회 / IP 30회 */
    private LoginRateLimiter limiter() {
        return new LoginRateLimiter(new FixedWindowCounter(redisTemplate), 10, 5, 30);
    }

    private void stub(String key, long count, long ttlMillis) {
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of(key)), anyString()))
                .thenReturn(List.of(count, ttlMillis));
    }

    @Test
    @DisplayName("한도 안이면 통과한다")
    void underLimitPasses() {
        stub(IP_KEY, 3, 600_000);
        stub(ACCT_KEY, 2, 600_000);

        assertThatCode(() -> limiter().check(IP, EMAIL)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("계정 한도를 넘으면 429 — 같은 계정을 여러 IP에서 두드리는 걸 막는다")
    void accountLimitExceeded() {
        stub(IP_KEY, 6, 600_000);      // IP는 여유 있음
        stub(ACCT_KEY, 6, 600_000);    // 계정이 초과

        assertThatThrownBy(() -> limiter().check(IP, EMAIL))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    @DisplayName("IP 한도를 넘으면 429 — 한 IP에서 계정을 바꿔가며 두드리는 걸 막는다")
    void ipLimitExceeded() {
        stub(IP_KEY, 31, 300_000);
        stub(ACCT_KEY, 1, 300_000);

        assertThatThrownBy(() -> limiter().check(IP, EMAIL))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    @DisplayName("초과해도 두 축을 모두 센다 — 한쪽만 세면 다른 축이 비어 우회로가 생긴다")
    void countsBothAxesEvenWhenExceeded() {
        stub(IP_KEY, 31, 600_000);
        stub(ACCT_KEY, 1, 600_000);

        assertThatThrownBy(() -> limiter().check(IP, EMAIL))
                .isInstanceOf(BusinessException.class);

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(IP_KEY)), anyString());
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(ACCT_KEY)), anyString());
    }

    @Test
    @DisplayName("Retry-After는 남은 TTL에서 계산한다 (두 축 중 긴 쪽)")
    void retryAfterFromLongerTtl() {
        stub(IP_KEY, 31, 90_000);      // 90초
        stub(ACCT_KEY, 1, 120_000);    // 120초

        assertThatThrownBy(() -> limiter().check(IP, EMAIL))
                .isInstanceOf(RateLimitedException.class)
                .extracting("retryAfterSeconds").isEqualTo(120L);
    }

    @Test
    @DisplayName("Redis가 죽으면 통과시킨다 (fail-open) — 방어 부재가 전원 로그인 불가보다 낫다")
    void redisDownFailsOpen() {
        when(redisTemplate.execute(any(RedisScript.class), any(), anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> limiter().check(IP, EMAIL)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("계정이 없으면 IP 축만 센다 (계정 키를 만들지 않는다)")
    void nullAccountCountsIpOnly() {
        stub(IP_KEY, 1, 600_000);

        assertThatCode(() -> limiter().check(IP, null)).doesNotThrowAnyException();

        verify(redisTemplate, never()).execute(any(RedisScript.class),
                eq(List.of(ACCT_KEY)), anyString());
    }

    @Test
    @DisplayName("계정 키는 소문자로 정규화한다 — 대소문자를 바꿔 한도를 우회하지 못하게")
    void accountKeyIsCaseInsensitive() {
        stub(IP_KEY, 1, 600_000);
        stub(ACCT_KEY, 1, 600_000);

        limiter().check(IP, "USER@TEST.COM");

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(ACCT_KEY)), anyString());
    }

    @Test
    @DisplayName("한도와 같은 횟수까지는 통과 — 초과부터 막는다")
    void exactlyAtLimitPasses() {
        stub(IP_KEY, 30, 600_000);
        stub(ACCT_KEY, 5, 600_000);

        assertThatCode(() -> limiter().check(IP, EMAIL)).doesNotThrowAnyException();
    }
}
