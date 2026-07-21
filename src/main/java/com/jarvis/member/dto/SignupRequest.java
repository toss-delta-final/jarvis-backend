package com.jarvis.member.dto;

import com.jarvis.member.Gender;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** A-1 — 형식 검증은 여기(Bean Validation), 상태 검증(중복)은 서비스 (03 §3-1) */
public record SignupRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,

        @NotBlank(message = "비밀번호를 입력해 주세요.")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$",
                message = "비밀번호는 8자 이상이며 영문과 숫자를 포함해야 합니다.")
        String password,

        @NotBlank(message = "닉네임을 입력해 주세요.")
        @Size(max = 50, message = "닉네임은 50자 이하여야 합니다.")
        String nickname,

        @NotNull(message = "성별을 선택해 주세요.")
        Gender gender,

        @NotNull(message = "출생일을 입력해 주세요.")
        @Past(message = "출생일은 과거 날짜여야 합니다.")
        LocalDate birthDate,

        @NotNull(message = "이용약관 동의는 필수입니다.")
        @AssertTrue(message = "이용약관 동의는 필수입니다.")
        Boolean agreeTerms,

        @NotNull(message = "개인정보처리방침 동의는 필수입니다.")
        @AssertTrue(message = "개인정보처리방침 동의는 필수입니다.")
        Boolean agreePrivacy
) {
    // 구 명세의 body guestId는 폐기(노션 A-1 2026-07-20) — 신원은 guest_id 쿠키에서 서버가 취하고,
    // FE가 보내는 잔여 guestId 필드는 역직렬화에서 무시된다(FE·BE 배포 순서 무관)
}
