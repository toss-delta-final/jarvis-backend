package com.jarvis.global.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProduceCircuitBreakerTest {

    /** 시간을 손으로 돌린다 — 실제 10초를 기다리는 테스트는 느리고 불안정하다 */
    private final AtomicLong clock = new AtomicLong();
    private final ProduceCircuitBreaker breaker = new ProduceCircuitBreaker(clock::get);

    private void advanceSeconds(long seconds) {
        clock.addAndGet(seconds * 1_000_000_000L);
    }

    @Test
    @DisplayName("평소에는 그냥 통과시킨다")
    void allowsWhenClosed() {
        assertThat(breaker.allowAttempt()).isTrue();
        assertThat(breaker.allowAttempt()).isTrue();
    }

    @Test
    @DisplayName("실패하면 차단된다 — 뒤따르는 요청은 produce를 시도하지 않는다")
    void blocksAfterFailure() {
        breaker.recordFailure();

        assertThat(breaker.allowAttempt()).isFalse();
        advanceSeconds(9);
        assertThat(breaker.allowAttempt()).isFalse();
    }

    @Test
    @DisplayName("차단 시간이 지나면 한 요청만 시험 통과한다 — 나머지는 계속 차단")
    void letsExactlyOneProbeThrough() {
        breaker.recordFailure();
        advanceSeconds(11);

        assertThat(breaker.allowAttempt()).isTrue();   // 시험 자격을 얻은 하나
        assertThat(breaker.allowAttempt()).isFalse();  // 나머지는 대기
        assertThat(breaker.allowAttempt()).isFalse();
    }

    @Test
    @DisplayName("시험이 성공하면 차단이 풀린다")
    void closesAfterSuccessfulProbe() {
        breaker.recordFailure();
        advanceSeconds(11);
        breaker.allowAttempt();

        breaker.recordSuccess();

        assertThat(breaker.allowAttempt()).isTrue();
        assertThat(breaker.allowAttempt()).isTrue();
    }

    @Test
    @DisplayName("시험이 또 실패하면 다시 차단된다 — 장애가 이어지는 동안 반복된다")
    void reopensAfterFailedProbe() {
        breaker.recordFailure();
        advanceSeconds(11);
        breaker.allowAttempt();

        breaker.recordFailure();

        assertThat(breaker.allowAttempt()).isFalse();
        advanceSeconds(11);
        assertThat(breaker.allowAttempt()).isTrue(); // 다음 시험
    }
}
