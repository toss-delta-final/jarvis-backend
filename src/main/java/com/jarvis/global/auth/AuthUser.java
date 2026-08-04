package com.jarvis.global.auth;

import com.jarvis.member.Role;
import java.time.Instant;

/**
 * SecurityContext principal — AT claim에서 복원한 인증 주체.
 *
 * <p>{@code issuedAt}은 무효화 마커와 대조하기 위한 것이다(07 §3-2 {@link TokenEpoch}) —
 * "이 신원이 언제 확정됐나"를 알아야 "그 뒤로 무효화됐나"를 판정할 수 있다.
 */
public record AuthUser(Long memberId, Role role, Instant issuedAt) {
}
