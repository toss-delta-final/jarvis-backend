package com.jarvis.global.ratelimit;

import com.jarvis.global.ratelimit.FixedWindowCounter.Attempt;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 업로드 URL 발급 제한 (S-6) — 발급은 파일 전송 없이 끝나므로 반복 호출이 값싸다.
 *
 * <p><b>회원 한 축만 센다.</b> 로그인과 달리 이미 인증을 통과한 판매자라 계정이 곧 책임 주체이고,
 * IP 축은 같은 사무실의 판매자들을 서로 막을 뿐이다.
 *
 * <p>발급 자체는 S3에 아무것도 만들지 않는다 — 막으려는 것은 저장 공간이 아니라
 * <b>서명 연산과 무제한 업로드 티켓</b>이다.
 */
@Slf4j
@Component
public class UploadRateLimiter {

    private static final String MEMBER_KEY = "ratelimit:upload:member:";

    private final FixedWindowCounter counter;
    private final Duration window;
    private final int memberLimit;

    public UploadRateLimiter(FixedWindowCounter counter,
                             @Value("${app.rate-limit.upload.window-minutes}") long windowMinutes,
                             @Value("${app.rate-limit.upload.member-limit}") int memberLimit) {
        this.counter = counter;
        this.window = Duration.ofMinutes(windowMinutes);
        this.memberLimit = memberLimit;
    }

    public void check(Long memberId) {
        Attempt attempt = counter.hit(MEMBER_KEY + memberId, window);
        if (attempt.count() <= memberLimit) {
            return;
        }
        long retryAfterSeconds = Math.max(1, attempt.ttlMillis() / 1000);
        log.warn("업로드 URL 발급 제한 — memberId={} count={} retryAfter={}s",
                memberId, attempt.count(), retryAfterSeconds);
        throw new RateLimitedException(retryAfterSeconds);
    }
}
