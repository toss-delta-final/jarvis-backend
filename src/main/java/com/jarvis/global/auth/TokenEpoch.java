package com.jarvis.global.auth;

import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 토큰 무효화 마커 (07 §3-2) — JWT의 약점 하나를 메운다.
 *
 * <p><b>메우는 약점</b>: AT는 서명만 맞으면 통과하므로, 탈취당한 AT는 <b>수명(30분)이 다할 때까지
 * 막을 방법이 없다.</b> 로그아웃해도, 비밀번호를 바꿔도 그 30분은 그대로 열려 있다. 여기에
 * "이 시각 이전에 발급된 토큰은 전부 무효"라는 마커를 두면 즉시 끊을 수 있다.
 *
 * <p><b>왜 카운터가 아니라 시각인가</b> (07 §3-2의 구 설계 "숫자 하나를 증가"에서 변경) —
 * 카운터는 두 가지가 걸린다. ① 발급할 때마다 현재 카운터를 <b>읽어야</b> 해서 로그인 경로에 Redis
 * 의존이 생긴다 ② 키가 TTL로 사라진 뒤 {@code INCR}하면 1부터 다시 시작해, 더 큰 값을 들고 있던
 * <b>기존 토큰들이 되살아난다</b> — 무효화가 조용히 풀리는 셈이다. 시각은 둘 다 없다. 토큰이 이미
 * 들고 있는 표준 {@code iat}를 그대로 쓰므로 <b>새 claim도, 발급 시 조회도 필요 없다.</b>
 *
 * <p><b>fail-open</b> — Redis가 죽으면 통과시킨다. 신원 자체는 토큰 서명으로 이미 확정돼 있어
 * "무효화만 잠깐 미적용"에 그친다. 반대로 막아버리면 Redis 장애가 <b>전원 로그아웃</b>이 된다.
 * 전통적인 세션 방식이라면 없는 선택지고, 이게 JWT를 남겨둔 값어치다.
 *
 * <p><b>TTL은 AT 최대 수명보다 길기만 하면 된다.</b> 그 뒤엔 무효화 대상이던 토큰이 전부 자연
 * 만료라 기억할 이유가 없다.
 */
@Slf4j
@Component
public class TokenEpoch {

    private static final String KEY_PREFIX = "auth:epoch:";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public TokenEpoch(StringRedisTemplate redisTemplate,
                      @Value("${app.auth.epoch-ttl-minutes}") long ttlMinutes) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    /**
     * 지금까지 발급된 이 회원의 AT를 전부 무효화한다. 로그아웃·비밀번호 변경·계정 정지·탈취 감지 시 호출.
     *
     * <p>실패해도 예외를 올리지 않는다 — 무효화를 못 했다고 로그아웃 자체가 실패하면 안 된다.
     */
    public void invalidate(long memberId) {
        try {
            redisTemplate.opsForValue()
                    .set(KEY_PREFIX + memberId, String.valueOf(Instant.now().getEpochSecond()), ttl);
        } catch (RuntimeException e) {
            log.warn("토큰 무효화 마커 기록 실패 — 해당 AT는 자연 만료까지 유효하다. memberId={}", memberId, e);
        }
    }

    /**
     * 이 토큰이 무효화된 뒤에 발급된 것인지 확인한다.
     *
     * <p>경계는 <b>{@code <}</b>다 — 무효화와 같은 초에 발급된 토큰은 살린다. 로그아웃 직후 곧바로
     * 다시 로그인했을 때 새 토큰이 같은 초에 걸려 죽는 일을 막기 위해서다. {@code iat}가 초 단위라
     * 1초 미만의 틈이 생기지만, 탈취된 토큰이 무효화와 같은 초에 발급됐을 리는 없다.
     */
    public boolean isRevoked(long memberId, Instant issuedAt) {
        if (issuedAt == null) {
            return false;
        }
        try {
            String marker = redisTemplate.opsForValue().get(KEY_PREFIX + memberId);
            return marker != null && issuedAt.getEpochSecond() < Long.parseLong(marker);
        } catch (RuntimeException e) {
            // fail-open — 못 읽으면 통과. 무효화 미적용이 전원 로그아웃보다 낫다
            log.warn("토큰 무효화 마커 조회 실패 — 무효화 미적용으로 통과. memberId={}", memberId, e);
            return false;
        }
    }
}
