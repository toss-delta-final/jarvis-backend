package com.jarvis.global.event;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 브로커가 죽은 걸 <b>이미 아는 동안</b>은 발행을 시도조차 하지 않는다 (08 D7 완화책).
 *
 * <p><b>왜 필요한가</b>: 폴백이 있어도 매 요청이 produce 타임아웃(최대 1초, 08 D7)을 물고 나서야
 * DB로 간다. 브로커가 몇 분씩 죽어 있으면 그 1초가 <b>모든 요청에</b> 붙어 요청 스레드가 쌓인다 —
 * 실측(2026-08-10)에서 브로커 정지 중 E-1이 1,301ms였고 그중 1,000ms가 이 대기였다.
 *
 * <p><b>동작</b> — 전기 차단기와 같다. 내려가면 전류를 흘리지 않되, 주기적으로 한 번씩 올려본다.
 * <pre>
 *   정상 → (발행 실패) → 차단(10초, 전부 즉시 DB) → (한 요청만 시험) → 성공이면 정상 / 실패면 재차단
 * </pre>
 * 5분 장애 기준으로 1초를 무는 요청이 <b>전부 → 30건</b>(10초에 1건)으로 줄고, 복구는 최대 10초 안에
 * 자동으로 감지된다.
 *
 * <p><b>인스턴스 로컬인 이유</b>: 공유 상태로 두면 발행 경로마다 Redis 왕복이 붙는다 — 빠르게 하려고
 * 넣은 장치가 스스로 비용을 만든다. 4대가 각자 발견해도 손해는 대당 10초에 1초뿐이다.
 * (S-1의 판단 근거인 {@code stream:behavior:degraded}는 성격이 달라 Redis에 남는다 — 08 D5·D7.)
 */
@Slf4j
@Component
public class ProduceCircuitBreaker {

    /** 차단 유지 시간. 브로커 재시작이 20~30초 걸리는 실측을 감안해, 복구를 한 사이클 안에 잡는 값 */
    static final Duration OPEN_DURATION = Duration.ofSeconds(10);

    /** 0이면 닫힘(정상). 그 외에는 이 시각까지 차단 */
    private final AtomicLong openUntilNanos = new AtomicLong();
    private final LongSupplier nanoTime;

    ProduceCircuitBreaker(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    public ProduceCircuitBreaker() {
        this(System::nanoTime);
    }

    /**
     * 지금 발행을 시도해도 되는가.
     *
     * <p>차단 창이 끝나면 <b>한 요청만</b> 시험 통과시킨다(CAS로 창을 연장해 나머지는 계속 차단) —
     * 그러지 않으면 창이 열리는 순간 밀려 있던 요청이 한꺼번에 1초씩 무는 일이 10초마다 반복된다.
     */
    public boolean allowAttempt() {
        long openUntil = openUntilNanos.get();
        if (openUntil == 0) {
            return true;
        }
        long now = nanoTime.getAsLong();
        if (now - openUntil < 0) {
            return false;
        }
        // 창 만료 — 시험 자격을 얻은 한 요청만 통과시키고, 그동안 다른 요청은 계속 차단한다
        return openUntilNanos.compareAndSet(openUntil, now + OPEN_DURATION.toNanos());
    }

    /** 발행이 실패했다 — 차단(또는 재차단) */
    public void recordFailure() {
        long previous = openUntilNanos.getAndSet(nanoTime.getAsLong() + OPEN_DURATION.toNanos());
        if (previous == 0) {
            log.warn("발행 차단기 열림 — {}초간 produce를 시도하지 않고 바로 DB로 적재한다",
                    OPEN_DURATION.toSeconds());
        }
    }

    /** 발행이 성공했다 — 차단 해제 */
    public void recordSuccess() {
        if (openUntilNanos.getAndSet(0) != 0) {
            log.info("발행 차단기 닫힘 — 브로커 정상, 스트림 경로로 복귀");
        }
    }
}
