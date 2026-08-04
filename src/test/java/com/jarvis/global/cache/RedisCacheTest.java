package com.jarvis.global.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 읽기 캐시 (07 §3-1) — 실제 Redis 없이 목으로 규칙 셋을 검증한다:
 * fail-open, 스탬피드 락, 락 폴백.
 */
@ExtendWith(MockitoExtension.class)
class RedisCacheTest {

    private static final String KEY = "v1:test:key";
    private static final String LOCK = KEY + ":lock";
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final TypeReference<List<Long>> TYPE = new TypeReference<>() { };

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;

    RedisCache cache;
    AtomicInteger loaderCalls;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cache = new RedisCache(redisTemplate, new ObjectMapper());
        loaderCalls = new AtomicInteger();
    }

    private List<Long> loader() {
        loaderCalls.incrementAndGet();
        return List.of(1L, 2L, 3L);
    }

    @Test
    @DisplayName("적중하면 로더를 부르지 않는다 — DB를 안 건드리는 게 캐시의 목적")
    void hitSkipsLoader() {
        when(valueOperations.get(KEY)).thenReturn("[7,8]");

        List<Long> result = cache.get(KEY, TTL, TYPE, this::loader);

        assertThat(result).containsExactly(7L, 8L);
        assertThat(loaderCalls).hasValue(0);
        verify(valueOperations, never()).setIfAbsent(eq(LOCK), anyString(), any());
    }

    @Test
    @DisplayName("미적중이면 락을 잡고 계산해 채운다")
    void missLoadsAndFills() {
        when(valueOperations.get(KEY)).thenReturn(null);
        when(valueOperations.setIfAbsent(eq(LOCK), anyString(), any())).thenReturn(true);

        List<Long> result = cache.get(KEY, TTL, TYPE, this::loader);

        assertThat(result).containsExactly(1L, 2L, 3L);
        assertThat(loaderCalls).hasValue(1);
        verify(valueOperations).set(KEY, "[1,2,3]", TTL);
        verify(redisTemplate).delete(LOCK); // 락은 반드시 해제된다
    }

    @Test
    @DisplayName("Redis가 죽어도 조회는 성공한다 (fail-open) — 캐시 장애가 서비스 장애가 되면 안 된다")
    void redisDownFallsOpen() {
        when(valueOperations.get(KEY)).thenThrow(new RedisConnectionFailureException("down"));
        when(valueOperations.setIfAbsent(eq(LOCK), anyString(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        List<Long> result = cache.get(KEY, TTL, TYPE, this::loader);

        assertThat(result).containsExactly(1L, 2L, 3L);
        assertThat(loaderCalls).hasValue(1);
    }

    @Test
    @DisplayName("깨진 값이 캐시에 있어도 DB로 우회한다 (역직렬화 실패 = 미적중 취급)")
    void corruptValueFallsBackToLoader() {
        when(valueOperations.get(KEY)).thenReturn("{잘못된 JSON");
        when(valueOperations.setIfAbsent(eq(LOCK), anyString(), any())).thenReturn(true);

        List<Long> result = cache.get(KEY, TTL, TYPE, this::loader);

        assertThat(result).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("스탬피드 — 락을 뺏기면 남이 채운 값을 기다렸다 쓴다 (중복 계산 없음)")
    void lockLostWaitsForWinner() {
        // 첫 조회는 미적중, 락 실패, 재조회에서 승자가 채운 값이 보인다
        when(valueOperations.get(KEY)).thenReturn(null, "[9]");
        when(valueOperations.setIfAbsent(eq(LOCK), anyString(), any())).thenReturn(false);

        List<Long> result = cache.get(KEY, TTL, TYPE, this::loader);

        assertThat(result).containsExactly(9L);
        assertThat(loaderCalls).hasValue(0); // 승자가 계산했으므로 나는 안 한다
    }

    @Test
    @DisplayName("스탬피드 — 승자가 끝내 못 채우면 직접 계산한다 (락 홀더 사망 시 무한 대기 방지)")
    void lockLostButNeverFilledFallsBack() {
        when(valueOperations.get(KEY)).thenReturn(null);
        when(valueOperations.setIfAbsent(eq(LOCK), anyString(), any())).thenReturn(false);

        List<Long> result = cache.get(KEY, TTL, TYPE, this::loader);

        assertThat(result).containsExactly(1L, 2L, 3L);
        assertThat(loaderCalls).hasValue(1);
        verify(redisTemplate, never()).delete(LOCK); // 내 락이 아니므로 남의 락을 풀지 않는다
    }

    @Test
    @DisplayName("로더가 던지면 락은 풀되 예외는 그대로 올린다")
    void loaderFailureReleasesLock() {
        when(valueOperations.get(KEY)).thenReturn(null);
        when(valueOperations.setIfAbsent(eq(LOCK), anyString(), any())).thenReturn(true);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> cache.get(KEY, TTL, TYPE, () -> {
                    throw new IllegalStateException("DB 장애");
                }))).isInstanceOf(IllegalStateException.class);

        verify(redisTemplate, times(1)).delete(LOCK);
        verify(valueOperations, never()).set(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("evict 실패는 삼킨다 — TTL이 최후의 그물이다")
    void evictSwallowsFailure() {
        when(redisTemplate.delete(KEY)).thenThrow(new RedisConnectionFailureException("down"));

        cache.evict(KEY); // 예외가 새어나오지 않는다
    }
}
