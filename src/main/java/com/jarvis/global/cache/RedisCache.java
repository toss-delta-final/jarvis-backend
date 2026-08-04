package com.jarvis.global.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 읽기 캐시 공용 진입점 (07 §3-1) — cache-aside.
 *
 * <p><b>원본은 항상 DB</b>이고 여기 있는 건 사본이다. 그래서 규칙 셋을 강제한다:
 * <ul>
 *   <li><b>fail-open</b> — Redis 예외는 삼키고 DB로 넘어간다. 캐시 장애가 조회 실패가 되면 안 된다.
 *       전역 커맨드 타임아웃(2s)과 별개로, 캐시 경로는 실패해도 사용자에게는 "조금 느림"이어야 한다.</li>
 *   <li><b>스탬피드 방지</b> — TTL이 만료되는 순간 동시 요청이 같은 무거운 집계를 DB에 몰아넣는다.
 *       SETNX 락을 잡은 한 요청만 계산하고, 못 잡은 요청은 잠깐 기다렸다 다시 읽는다.</li>
 *   <li><b>TTL 필수</b> — 인자로 강제한다. TTL 없는 키는 메모리가 새는 유일한 경로다(07 §1-4).</li>
 * </ul>
 *
 * <p>락을 못 잡고 재조회도 비면 <b>직접 계산한다</b>(폴백). 락 홀더가 죽었을 때 요청이 멈추지 않게 —
 * 중복 계산 몇 번이 무한 대기보다 낫다.
 *
 * <p>Spring {@code @Cacheable}을 쓰지 않는 이유: fail-open·스탬피드 락·버전 프리픽스를 손에 쥐어야 하는데
 * 선언형으로는 그 셋을 제어하기 어렵다. 주변 코드(채팅 세션·추천 목록)도 {@code StringRedisTemplate}을
 * 직접 쓰고 있어 방식이 일관된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCache {

    /** 락 자체 TTL — 홀더가 죽어도 이 시간 뒤 자동 해제된다(데드락 방지) */
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    /** 락을 못 잡았을 때 재조회까지의 대기 — 계산이 끝나길 잠깐 기다린다 */
    private static final long RETRY_WAIT_MILLIS = 50;
    private static final int RETRY_COUNT = 4;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 캐시에 있으면 그것을, 없으면 {@code loader}로 만들어 채운 뒤 돌려준다.
     *
     * @param key 버전 프리픽스를 포함한 완성된 키 (예: {@code v1:popular:ids})
     * @param ttl 만료 — 캐시별 근거는 07 §2-1
     */
    public <T> T get(String key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
        T hit = read(key, type);
        if (hit != null) {
            return hit;
        }
        if (!acquireLock(key)) {
            T afterWait = waitAndRead(key, type);
            if (afterWait != null) {
                return afterWait;
            }
            // 락 홀더가 죽었거나 계산이 오래 걸린다 — 기다리느니 직접 만든다
            return loader.get();
        }
        try {
            T loaded = loader.get();
            write(key, ttl, loaded);
            return loaded;
        } finally {
            releaseLock(key);
        }
    }

    /** 쓰기 경로에서 명시적으로 무효화할 때. 실패는 무시한다 — TTL이 최후의 그물이다 */
    public void evict(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException e) {
            log.warn("캐시 무효화 실패 — TTL 소멸에 위임. key={}", key, e);
        }
    }

    private <T> T read(String key, TypeReference<T> type) {
        try {
            String raw = redisTemplate.opsForValue().get(key);
            return raw == null ? null : objectMapper.readValue(raw, type);
        } catch (Exception e) {
            // 역직렬화 실패(스키마 변경 등)도 여기로 온다 — 캐시를 없는 셈 치고 DB로 간다
            log.warn("캐시 조회 실패 — DB로 우회. key={}", key, e);
            return null;
        }
    }

    private <T> void write(String key, Duration ttl, T value) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.warn("캐시 저장 실패 — 조회 결과는 그대로 반환. key={}", key, e);
        }
    }

    private <T> T waitAndRead(String key, TypeReference<T> type) {
        for (int i = 0; i < RETRY_COUNT; i++) {
            try {
                Thread.sleep(RETRY_WAIT_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            T value = read(key, type);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private boolean acquireLock(String key) {
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(lockKey(key), "1", LOCK_TTL));
        } catch (RuntimeException e) {
            // Redis가 죽었으면 락도 못 잡는다 — 계산은 해야 하므로 "잡은 것"으로 진행한다
            log.warn("캐시 락 획득 실패 — 락 없이 계산. key={}", key, e);
            return true;
        }
    }

    private void releaseLock(String key) {
        try {
            redisTemplate.delete(lockKey(key));
        } catch (RuntimeException e) {
            log.warn("캐시 락 해제 실패 — TTL로 소멸. key={}", key, e);
        }
    }

    private static String lockKey(String key) {
        return key + ":lock";
    }
}
