package com.jarvis.global.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** 토큰 무효화 마커 (07 §3-2) — 발급 시각이 마커보다 이르면 죽는다 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // opsForValue() 스텁이 안 쓰이는 케이스가 있다
class TokenEpochTest {

    private static final long MEMBER_ID = 7L;
    private static final String KEY = "auth:epoch:7";
    /** 마커가 찍힌 시각 */
    private static final Instant REVOKED_AT = Instant.ofEpochSecond(1_700_000_000L);

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    private TokenEpoch tokenEpoch;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        tokenEpoch = new TokenEpoch(redisTemplate, 35);
    }

    private void markerAt(Instant instant) {
        when(valueOps.get(KEY)).thenReturn(String.valueOf(instant.getEpochSecond()));
    }

    @Test
    @DisplayName("무효화 전에 발급된 토큰은 죽는다 — 이게 이 기능의 존재 이유다")
    void tokenIssuedBeforeRevocationIsRejected() {
        markerAt(REVOKED_AT);

        assertThat(tokenEpoch.isRevoked(MEMBER_ID, REVOKED_AT.minusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("무효화 후에 발급된 토큰은 통과한다 — 로그아웃 뒤 재로그인이 막히면 안 된다")
    void tokenIssuedAfterRevocationPasses() {
        markerAt(REVOKED_AT);

        assertThat(tokenEpoch.isRevoked(MEMBER_ID, REVOKED_AT.plusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("같은 초에 발급된 토큰은 살린다 — 로그아웃 직후 재로그인이 초 경계에 걸려 죽지 않게")
    void tokenIssuedInSameSecondSurvives() {
        markerAt(REVOKED_AT);

        assertThat(tokenEpoch.isRevoked(MEMBER_ID, REVOKED_AT)).isFalse();
    }

    @Test
    @DisplayName("마커가 없으면 통과 — 무효화된 적이 없거나 TTL로 사라진 경우")
    void noMarkerPasses() {
        when(valueOps.get(KEY)).thenReturn(null);

        assertThat(tokenEpoch.isRevoked(MEMBER_ID, REVOKED_AT)).isFalse();
    }

    @Test
    @DisplayName("iat 없는 토큰은 통과 — 하위호환(구 토큰을 죽이지 않는다)")
    void tokenWithoutIssuedAtPasses() {
        assertThat(tokenEpoch.isRevoked(MEMBER_ID, null)).isFalse();
    }

    @Test
    @DisplayName("Redis가 죽으면 통과시킨다 (fail-open) — 무효화 미적용이 전원 로그아웃보다 낫다")
    void redisDownFailsOpen() {
        when(valueOps.get(KEY)).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(tokenEpoch.isRevoked(MEMBER_ID, REVOKED_AT.minusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("무효화는 TTL을 달아 기록한다 — TTL이 없으면 키가 영원히 남는다")
    void invalidateWritesWithTtl() {
        tokenEpoch.invalidate(MEMBER_ID);

        verify(valueOps).set(eq(KEY), anyString(), eq(Duration.ofMinutes(35)));
    }

    @Test
    @DisplayName("무효화가 실패해도 예외를 올리지 않는다 — 마커를 못 찍었다고 로그아웃이 실패하면 안 된다")
    void invalidateSwallowsRedisFailure() {
        // set()은 void라 doThrow 형태로 스텁한다
        doThrow(new RedisConnectionFailureException("down"))
                .when(valueOps).set(anyString(), anyString(), eq(Duration.ofMinutes(35)));

        assertThatCode(() -> tokenEpoch.invalidate(MEMBER_ID)).doesNotThrowAnyException();
    }
}
