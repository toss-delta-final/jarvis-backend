package com.jarvis.global.auth;

import com.jarvis.member.Role;

/** SecurityContext principal — AT claim에서 복원한 인증 주체 */
public record AuthUser(Long memberId, Role role) {
}
