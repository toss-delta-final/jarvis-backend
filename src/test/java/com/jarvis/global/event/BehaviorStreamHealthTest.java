package com.jarvis.global.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class BehaviorStreamHealthTest {

    private static final String CONNECTED = "stream:behavior:connected";
    private static final String DEGRADED = "stream:behavior:degraded";

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;

    @InjectMocks BehaviorStreamHealth health;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private static String secondsAgo(long seconds) {
        return Long.toString(Instant.now().getEpochSecond() - seconds);
    }

    @Test
    @DisplayName("연결이 이어지는 동안은 시작 시각을 덮어쓰지 않는다 — TTL만 늘린다")
    void keepsConnectedSinceWhileContinuous() {
        when(redisTemplate.expire(eq(CONNECTED), any(Duration.class))).thenReturn(true);

        health.markConnected();

        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("신호가 만료됐다 다시 켜지면 시작 시각을 새로 찍는다 — 연속성이 끊긴 것이다")
    void restartsConnectedSinceAfterGap() {
        when(redisTemplate.expire(eq(CONNECTED), any(Duration.class))).thenReturn(false);

        health.markConnected();

        verify(valueOperations).set(eq(CONNECTED), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("연속 연결이 집계 창(30분)을 넘겨야 집계를 믿는다")
    void trustsAggregateOnlyAfterFullWindow() {
        when(redisTemplate.hasKey(DEGRADED)).thenReturn(false);
        when(valueOperations.get(CONNECTED)).thenReturn(secondsAgo(31 * 60));

        assertThat(health.canTrustAggregate()).isTrue();
    }

    @Test
    @DisplayName("연결된 지 얼마 안 됐으면 안 믿는다 — Redis 재시작으로 집계가 비었을 수 있다")
    void doesNotTrustFreshlyReconnectedStream() {
        when(redisTemplate.hasKey(DEGRADED)).thenReturn(false);
        when(valueOperations.get(CONNECTED)).thenReturn(secondsAgo(60));

        assertThat(health.canTrustAggregate()).isFalse();
    }

    @Test
    @DisplayName("연결 신호가 아예 없으면 안 믿는다 — 컨슈머가 죽었거나 브로커와 못 통하고 있다")
    void doesNotTrustWhenSignalMissing() {
        when(redisTemplate.hasKey(DEGRADED)).thenReturn(false);
        when(valueOperations.get(CONNECTED)).thenReturn(null);

        assertThat(health.canTrustAggregate()).isFalse();
    }

    @Test
    @DisplayName("강등 마커가 있으면 연결이 아무리 오래됐어도 안 믿는다 — 그 구간 이벤트가 스트림에 없다")
    void doesNotTrustWhileDegraded() {
        when(redisTemplate.hasKey(DEGRADED)).thenReturn(true);

        assertThat(health.canTrustAggregate()).isFalse();
        verify(valueOperations, never()).get(anyString());
    }

    @Test
    @DisplayName("Redis가 죽으면 안 믿는다 — 어차피 집계도 Redis에 있어 읽을 수 없다")
    void doesNotTrustWhenRedisFails() {
        when(redisTemplate.hasKey(DEGRADED)).thenThrow(new RuntimeException("redis down"));

        assertThat(health.canTrustAggregate()).isFalse();
    }
}
