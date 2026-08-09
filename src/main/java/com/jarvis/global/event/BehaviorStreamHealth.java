package com.jarvis.global.event;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 행동 이벤트 스트림이 지금 살아 있는가 (08 D5) — 스트림에서 파생한 실시간 지표를 믿어도 되는지 판정.
 *
 * <p><b>왜 필요한가</b>: 파이프라인이 멈추면 파생 지표(Redis 집계)는 <b>에러가 아니라 조용히 낡는다.</b>
 * 키는 그대로 살아 있으니 읽는 쪽은 멈춘 줄 모른다. 그래서 두 신호를 각각 기록하고 <b>둘 다</b> 성해야만
 * 파생 지표를 신뢰한다 — 한쪽만으로는 부족하다:
 * <ul>
 *   <li><b>컨슈머 생존</b>({@link #markConsumerAlive()}) — 레코드를 처리했거나 유휴 폴링을 돌고 있다.
 *       컨슈머가 죽은 경우를 잡는다. 브로커가 죽어도 폴링은 계속 도므로 이것만으로는 부족하다.</li>
 *   <li><b>발행 성공</b>({@link #markProduceFailure()}의 부재) — 프로듀서가 브로커에 닿고 있다.
 *       브로커가 죽은 경우를 잡는다. 이때 이벤트는 DB로 폴백되므로(08 D7) 스트림 집계에는 안 들어온다.</li>
 * </ul>
 *
 * <p><b>강등 마커의 TTL이 긴 이유</b>: 발행이 다시 성공해도 장애 구간의 이벤트는 스트림에 없다.
 * 집계 창(30분)만큼 지나 그 구멍이 창 밖으로 밀려나야 스트림이 다시 정확해지므로, 마지막 실패로부터
 * 창 길이만큼은 계속 DB 폴백을 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BehaviorStreamHealth {

    private static final String ALIVE_KEY = "stream:behavior:alive";
    private static final String DEGRADED_KEY = "stream:behavior:degraded";

    /** 컨슈머 생존 신호의 수명 — 갱신 주기(레코드 처리 또는 유휴 이벤트)보다 넉넉히 */
    private static final Duration ALIVE_TTL = Duration.ofSeconds(90);
    /** 집계 창(30분)과 같다 — 위 주석 참조 */
    private static final Duration DEGRADED_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;

    /** 컨슈머가 살아 폴링 중임을 알린다. 레코드가 없어도(유휴) 갱신된다 */
    public void markConsumerAlive() {
        try {
            redisTemplate.opsForValue().set(ALIVE_KEY, "1", ALIVE_TTL);
        } catch (Exception e) {
            log.debug("스트림 생존 신호 기록 실패 — 무시(다음 폴에서 재시도)", e);
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
     * 스트림 파생 지표를 믿어도 되는가.
     *
     * <p>Redis 자체가 죽으면 {@code false} — 어차피 파생 지표도 Redis에 있어 읽을 수 없다.
     */
    public boolean isLive() {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(ALIVE_KEY))
                    && !Boolean.TRUE.equals(redisTemplate.hasKey(DEGRADED_KEY));
        } catch (Exception e) {
            log.warn("스트림 상태 조회 실패 — DB 폴백으로 판정", e);
            return false;
        }
    }
}
