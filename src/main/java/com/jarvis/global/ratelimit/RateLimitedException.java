package com.jarvis.global.ratelimit;

import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;

/** {@code Retry-After} 헤더를 실으려고 예외에 남은 시간을 담는다 (07 §3-4) */
public class RateLimitedException extends BusinessException {

    private final long retryAfterSeconds;

    RateLimitedException(long retryAfterSeconds) {
        super(ErrorCode.RATE_LIMITED);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
