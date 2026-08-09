package com.jarvis.global.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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

    @Test
    @DisplayName("evictAfterCommit — 트랜잭션 동기화가 없으면 즉시 지운다 (경합이 없으므로)")
    void evictAfterCommitWithoutTransactionEvictsImmediately() {
        cache.evictAfterCommit(KEY);

        verify(redisTemplate).delete(KEY);
    }

    @Test
    @DisplayName("evictAfterCommit — 트랜잭션 중엔 커밋 후에만 지운다 (커밋 전 되캐시 경합 방지)")
    void evictAfterCommitDefersUntilCommit() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            cache.evictAfterCommit(KEY);
            verify(redisTemplate, never()).delete(KEY); // 아직 커밋 전 — 지우면 안 된다

            org.springframework.transaction.support.TransactionSynchronizationManager
                    .getSynchronizations().forEach(sync -> sync.afterCommit());
            verify(redisTemplate).delete(KEY);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .clearSynchronization();
        }
    }

    // ---- 배치(getAll) — id 단위 키, MGET 1회, 미스분만 로더 ----

    private static final String PREFIX = "v1:test:stats:";
    private static final TypeReference<Long> ITEM_TYPE = new TypeReference<>() { };

    /** 로더가 받은 미스 id를 기록하고, 짝수 id만 값(id×10)을 돌려준다 — 홀수 id는 absent 채움 검증용 */
    private List<Long> loadedWith;

    private Map<Long, Long> batchLoader(List<Long> missed) {
        loaderCalls.incrementAndGet();
        loadedWith = missed;
        return missed.stream().filter(id -> id % 2 == 0)
                .collect(java.util.stream.Collectors.toMap(id -> id, id -> id * 10));
    }

    @Test
    @DisplayName("배치 — 전부 적중이면 로더를 부르지 않는다")
    void getAllHitSkipsLoader() {
        when(valueOperations.multiGet(List.of(PREFIX + "1", PREFIX + "2"))).thenReturn(
                java.util.Arrays.asList("10", "20"));

        Map<Long, Long> result = cache.getAll(PREFIX, List.of(1L, 2L), TTL, ITEM_TYPE,
                this::batchLoader, 0L);

        assertThat(result).isEqualTo(Map.of(1L, 10L, 2L, 20L));
        assertThat(loaderCalls).hasValue(0);
    }

    @Test
    @DisplayName("배치 — 미스분만 로더로 채우고, 로더 결과에 없는 id는 absent 값으로 채워 캐시한다")
    void getAllPartialMissLoadsOnlyMissed() {
        when(valueOperations.multiGet(List.of(PREFIX + "1", PREFIX + "2", PREFIX + "3")))
                .thenReturn(java.util.Arrays.asList("10", null, null));

        Map<Long, Long> result = cache.getAll(PREFIX, List.of(1L, 2L, 3L), TTL, ITEM_TYPE,
                this::batchLoader, 0L);

        assertThat(result).isEqualTo(Map.of(1L, 10L, 2L, 20L, 3L, 0L));
        assertThat(loadedWith).containsExactly(2L, 3L); // 적중한 1L은 로더에 안 간다
        verify(valueOperations).set(PREFIX + "2", "20", TTL);
        verify(valueOperations).set(PREFIX + "3", "0", TTL); // 원본에 없어도 캐시 — 반복 미스 방지
    }

    @Test
    @DisplayName("배치 — MGET이 죽어도 조회는 성공한다 (fail-open, 전량 로더)")
    void getAllRedisDownFallsOpen() {
        when(valueOperations.multiGet(any())).thenThrow(new RedisConnectionFailureException("down"));

        Map<Long, Long> result = cache.getAll(PREFIX, List.of(2L, 3L), TTL, ITEM_TYPE,
                this::batchLoader, 0L);

        assertThat(result).isEqualTo(Map.of(2L, 20L, 3L, 0L));
        assertThat(loaderCalls).hasValue(1);
    }

    @Test
    @DisplayName("배치 — 깨진 엔트리는 그 키만 미스로 취급한다")
    void getAllCorruptEntryTreatedAsMiss() {
        when(valueOperations.multiGet(List.of(PREFIX + "1", PREFIX + "2")))
                .thenReturn(java.util.Arrays.asList("{깨진 값", "10"));

        Map<Long, Long> result = cache.getAll(PREFIX, List.of(1L, 2L), TTL, ITEM_TYPE,
                this::batchLoader, 0L);

        assertThat(result).isEqualTo(Map.of(1L, 0L, 2L, 10L));
        assertThat(loadedWith).containsExactly(1L);
    }

    @Test
    @DisplayName("배치 — 저장 실패는 삼키고 로더 값을 그대로 돌려준다")
    void getAllWriteFailureSwallowed() {
        when(valueOperations.multiGet(any()))
                .thenReturn(java.util.Arrays.asList((String) null));
        doThrow(new RedisConnectionFailureException("down"))
                .when(valueOperations).set(anyString(), anyString(), eq(TTL));

        Map<Long, Long> result = cache.getAll(PREFIX, List.of(2L), TTL, ITEM_TYPE,
                this::batchLoader, 0L);

        assertThat(result).isEqualTo(Map.of(2L, 20L));
    }
}
