package com.jarvis.member.dto;

import jakarta.validation.constraints.NotBlank;

/** A-2 */
public record LoginRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
        String email,

        @NotBlank(message = "비밀번호를 입력해 주세요.")
        String password,

        String guestId
) {
}
