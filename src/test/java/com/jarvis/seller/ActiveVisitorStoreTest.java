package com.jarvis.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jarvis.global.event.BehaviorStreamHealth;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

@ExtendWith(MockitoExtension.class)
class ActiveVisitorStoreTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ZSetOperations<String, String> zSetOperations;
    @Mock BehaviorStreamHealth streamHealth;

    @InjectMocks ActiveVisitorStore store;

    private static final Long BRAND_ID = 7L;
    private static final LocalDateTime SINCE = LocalDateTime.now().minusMinutes(30);

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
    }

    @Test
    @DisplayName("집계를 믿을 수 있으면 창 밖을 잘라내고 남은 고유 세션 수를 센다")
    void countsDistinctSessionsInWindow() {
        when(streamHealth.canTrustAggregate()).thenReturn(true);
        when(zSetOperations.zCard("visitors:7")).thenReturn(17L);

        assertThat(store.count(BRAND_ID, SINCE)).hasValue(17L);
        verify(zSetOperations).removeRangeByScore(eq("visitors:7"), eq(0.0), anyDouble());
    }

    @Test
    @DisplayName("스트림을 믿을 수 없으면 Redis를 읽지 않고 비운 채 돌려준다 — 호출부가 DB로 폴백한다")
    void returnsEmptyWhenStreamIsNotLive() {
        when(streamHealth.canTrustAggregate()).thenReturn(false);

        assertThat(store.count(BRAND_ID, SINCE)).isEmpty();
        verifyNoInteractions(zSetOperations);
    }

    @Test
    @DisplayName("Redis가 죽어도 예외를 올리지 않는다 — 비운 채 돌려주고 DB 폴백에 맡긴다")
    void returnsEmptyOnRedisFailure() {
        when(streamHealth.canTrustAggregate()).thenReturn(true);
        when(zSetOperations.zCard(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThat(store.count(BRAND_ID, SINCE)).isEmpty();
    }

    @Test
    @DisplayName("같은 세션을 여러 번 기록해도 집합이라 부풀지 않는다 — at-least-once 면역 (08 D3)")
    void recordIsIdempotentBySetSemantics() {
        LocalDateTime at = LocalDateTime.now();

        store.record(BRAND_ID, "sess-1", at);
        store.record(BRAND_ID, "sess-1", at);

        // 같은 member로 두 번 ZADD — Redis 집합 의미상 원소는 하나다
        verify(zSetOperations, org.mockito.Mockito.times(2))
                .add(eq("visitors:7"), eq("sess-1"), anyDouble());
        verify(redisTemplate, org.mockito.Mockito.times(2)).expire(eq("visitors:7"), any());
    }

    @Test
    @DisplayName("기록이 실패해도 예외를 올리지 않는다 — 집계 한 건이 컨슈머를 멈추면 안 된다")
    void recordSwallowsFailure() {
        when(zSetOperations.add(anyString(), anyString(), anyDouble()))
                .thenThrow(new RuntimeException("redis down"));

        store.record(BRAND_ID, "sess-1", LocalDateTime.now());
    }
}
