package com.jarvis.member.dto;

import jakarta.validation.constraints.NotBlank;

/** A-2 */
public record LoginRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
        String email,

        @NotBlank(message = "비밀번호를 입력해 주세요.")
        String password
) {
    // 구 명세의 body guestId는 폐기(노션 A-2 2026-07-20) — guest_id 쿠키에서 서버가 취한다
}
