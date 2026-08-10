package com.jarvis.seller;

import com.jarvis.global.event.BehaviorStreamHealth;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * S-1 실시간 방문자의 라이브 집계 (08 D4·D5) — 브랜드별 "최근 30분에 활동한 세션" 집합.
 *
 * <p>ZSET인 이유: 정의가 <b>슬라이딩 창 안의 고유 세션 수</b>라 (member=sessionKey, score=시각)이
 * 그대로 답이 된다. 창 밖은 점수 범위로 잘라내고 남은 크기를 세면 끝이다.
 *
 * <p><b>중복 전달에 면역이다</b> — {@code ZADD}는 집합 연산이라 같은 세션이 여러 번 들어와도
 * 원소는 하나다. at-least-once가 숫자를 부풀리지 못한다(08 D3). 스트림에서 카운터가 아니라
 * 이 지표를 고른 이유이기도 하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActiveVisitorStore {

    private static final String KEY_PREFIX = "visitors:";
    /** 창(30분)보다 길게 — 마지막 이벤트 뒤 창이 다 지나도록 키를 살려두고, 그 뒤엔 스스로 사라진다 */
    private static final Duration KEY_TTL = Duration.ofMinutes(40);

    private final StringRedisTemplate redisTemplate;
    private final BehaviorStreamHealth streamHealth;

    public void record(Long brandId, String sessionKey, LocalDateTime at) {
        String key = KEY_PREFIX + brandId;
        try {
            redisTemplate.opsForZSet().add(key, sessionKey, epochSeconds(at));
            redisTemplate.expire(key, KEY_TTL);
        } catch (Exception e) {
            // 집계 한 건 때문에 컨슈머를 실패시키지 않는다 — 실패가 쌓이면 생존 신호가 아니라
            // 값의 정확도 문제이고, 그건 창이 지나면 자연히 회복된다
            log.warn("실시간 방문자 기록 실패 (brandId={})", brandId, e);
        }
    }

    /**
     * @return 창 안의 고유 세션 수. <b>스트림을 믿을 수 없으면 비어 있다</b> — 호출자는 그때
     *         DB 집계로 폴백한다(08 D5). "0명"과 "모름"을 구분하려고 {@code OptionalLong}이다.
     */
    public OptionalLong count(Long brandId, LocalDateTime since) {
        if (!streamHealth.canTrustAggregate()) {
            return OptionalLong.empty();
        }
        String key = KEY_PREFIX + brandId;
        try {
            redisTemplate.opsForZSet().removeRangeByScore(key, 0, epochSeconds(since));
            Long size = redisTemplate.opsForZSet().zCard(key);
            return OptionalLong.of(size == null ? 0L : size);
        } catch (Exception e) {
            log.warn("실시간 방문자 조회 실패 — DB 폴백 (brandId={})", brandId, e);
            return OptionalLong.empty();
        }
    }

    private static double epochSeconds(LocalDateTime at) {
        return at.atZone(ZoneId.systemDefault()).toEpochSecond();
    }
}
