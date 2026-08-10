package com.jarvis.global.event;

import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 스트림에서 파생한 집계(S-1 실시간 방문자)를 <b>믿어도 되는지</b> 판정한다 (08 D5).
 *
 * <p><b>왜 필요한가</b>: 파이프라인이 멈추면 파생 집계는 에러가 아니라 <b>조용히 낡는다.</b>
 * 키는 그대로 살아 있으니 읽는 쪽은 멈춘 줄 모른다. 그래서 신호 두 개를 두고 <b>둘 다</b> 성립할 때만
 * 값을 신뢰한다:
 * <ul>
 *   <li><b>브로커 연결</b>({@link #markConnected()}) — 컨슈머가 브로커와 실제로 통하고 있다.
 *       판정은 {@link BehaviorStreamProbe}가 컨슈머 지표로 한다.</li>
 *   <li><b>발행 성공</b>({@link #markProduceFailure()}의 부재) — 프로듀서가 이벤트를 토픽에 싣고 있다.
 *       발행이 실패한 구간은 DB로 폴백되므로(08 D7) 스트림 집계에 들어오지 않는다.</li>
 * </ul>
 *
 * <p><b>연결 신호에 "언제부터"를 담는 이유</b>(2026-08-10 추가): 신호가 <i>지금</i> 있다는 것만으로는
 * 부족하다. Redis가 재시작되면 집계 자체가 비어버리는데 신호는 곧 다시 켜져 <b>빈 집계를 믿게 된다.</b>
 * 그래서 값에 <b>연속 연결이 시작된 시각</b>을 담고, 그게 집계 창(30분)보다 짧으면 아직 믿지 않는다 —
 * 창이 실제 관측으로 다 채워질 때까지 기다리는 것이다. Redis 재시작·컨슈머 전면 정지·네트워크 단절이
 * 이 한 규칙으로 함께 걸린다(롤링 배포는 남은 인스턴스가 갱신하므로 연속성이 끊기지 않는다).
 *
 * <p><b>강등 마커의 TTL이 긴 이유</b>: 발행이 다시 성공해도 장애 구간의 이벤트는 스트림에 없다.
 * 그 구멍이 창 밖으로 밀려나야 정확해지므로, 마지막 실패로부터 창 길이만큼은 DB 폴백을 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BehaviorStreamHealth {

    /**
     * 값은 <b>연속 연결이 시작된 epoch 초</b>다. 이름이 {@code alive}가 아닌 이유 —
     * 종전 이름은 "폴링 중"을 뜻하면서 "스트림 정상"으로 읽혀 실제로 오진을 불렀다(2026-08-10).
     */
    private static final String CONNECTED_KEY = "stream:behavior:connected";
    private static final String DEGRADED_KEY = "stream:behavior:degraded";

    /** 프로브 주기(30초)보다 넉넉히 — 한 번 걸러도 만료되지 않게 */
    private static final Duration CONNECTED_TTL = Duration.ofSeconds(90);
    /** S-1 집계 창과 같다 — 이만큼 연속으로 관측해야 집계가 완전해진다 */
    private static final Duration WINDOW = Duration.ofMinutes(30);
    /** 강등도 같은 근거로 창 길이만큼 유지한다 */
    private static final Duration DEGRADED_TTL = WINDOW;

    private final StringRedisTemplate redisTemplate;

    /**
     * 브로커 연결이 확인됐음을 알린다. <b>연속성이 끊겼다 다시 붙으면 시작 시각이 리셋된다</b> —
     * 키가 살아 있으면 TTL만 늘리고, 없으면(만료됐다는 뜻) 지금을 새 시작점으로 적는다.
     */
    public void markConnected() {
        try {
            if (!Boolean.TRUE.equals(redisTemplate.expire(CONNECTED_KEY, CONNECTED_TTL))) {
                redisTemplate.opsForValue()
                        .set(CONNECTED_KEY, Long.toString(Instant.now().getEpochSecond()), CONNECTED_TTL);
                log.info("스트림 연결 신호 재시작 — 집계 창({}분)이 채워질 때까지 DB 폴백", WINDOW.toMinutes());
            }
        } catch (Exception e) {
            log.debug("스트림 연결 신호 기록 실패 — 무시(다음 주기에 재시도)", e);
        }
    }

    /** 발행이 실패해 DB로 폴백했음을 알린다 — 그 구간 이벤트는 스트림에 없다 */
    public void markProduceFailure() {
        try {
            redisTemplate.opsForValue().set(DEGRADED_KEY, "1", DEGRADED_TTL);
        } catch (Exception e) {
            log.debug("스트림 강등 마커 기록 실패 — 무시", e);
        }
    }

    /**
     * 스트림 파생 집계를 믿어도 되는가.
     *
     * <p>Redis 자체가 죽으면 {@code false} — 어차피 파생 집계도 Redis에 있어 읽을 수 없다.
     */
    public boolean canTrustAggregate() {
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(DEGRADED_KEY))) {
                return false;
            }
            String connectedSince = redisTemplate.opsForValue().get(CONNECTED_KEY);
            if (connectedSince == null) {
                return false;
            }
            long elapsed = Instant.now().getEpochSecond() - Long.parseLong(connectedSince);
            return elapsed >= WINDOW.toSeconds();
        } catch (Exception e) {
            log.warn("스트림 상태 조회 실패 — DB 폴백으로 판정", e);
            return false;
        }
    }
}
