package com.jarvis.global.ratelimit;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 고정 창 카운터 (07 §3-4) — 키 하나를 세고 남은 TTL을 함께 돌려준다. 한도 판정은 호출자 몫이다.
 *
 * <p>알고리즘은 <b>고정 창</b>이다. 창 경계에서 한도의 2배까지 통과할 수 있지만 남용 차단에는
 * 충분하고, 슬라이딩 로그처럼 요청 시각을 전부 저장하지 않아 메모리가 일정하다.
 *
 * <p>{@code INCR}과 {@code EXPIRE}를 <b>Lua로 원자화</b>한다. 둘로 나누면 첫 요청 직후 프로세스가
 * 죽었을 때 TTL이 없는 키가 남아 <b>영원히 카운트가 쌓인다</b> — 그 키는 영구 차단된다.
 *
 * <p>Redis 장애 시 <b>fail-open</b> — 못 세면 통과시킨다. "잠깐 방어가 없는 것"이 "전원 차단"보다 낫다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FixedWindowCounter {

    /** 카운터를 올리고 첫 요청에만 TTL을 건다. 반환: {현재 카운트, 남은 TTL(ms)} */
    @SuppressWarnings("rawtypes") // Redis Lua는 배열을 List로 돌려주는데 스크립트 타입은 raw만 받는다
    private static final RedisScript<List> INCR_WITH_TTL = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return {count, redis.call('PTTL', KEYS[1])}
            """, List.class);

    private final StringRedisTemplate redisTemplate;

    public Attempt hit(String key, Duration window) {
        try {
            List<?> result = redisTemplate.execute(INCR_WITH_TTL, List.of(key),
                    String.valueOf(window.toMillis()));
            if (result == null || result.size() < 2) {
                return Attempt.SKIPPED;
            }
            return new Attempt(((Number) result.get(0)).longValue(),
                    ((Number) result.get(1)).longValue());
        } catch (RuntimeException e) {
            // fail-open — 못 세면 통과시킨다. 방어가 잠깐 없는 게 전원 차단보다 낫다
            log.warn("시도 집계 실패 — 제한 없이 통과. key={}", key, e);
            return Attempt.SKIPPED;
        }
    }

    /** 세지 못한 경우(대상 없음·Redis 장애)는 한도 판정에서 제외되도록 count 0 */
    public record Attempt(long count, long ttlMillis) {
        public static final Attempt SKIPPED = new Attempt(0, 0);
    }
}
