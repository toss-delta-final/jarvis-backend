package com.jarvis.member;

import com.jarvis.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password; // BCrypt

    @Column(nullable = false, length = 50)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Gender gender;

    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate;

    @Column(name = "agreed_terms_at", nullable = false)
    private LocalDateTime agreedTermsAt;

    @Column(name = "agreed_privacy_at", nullable = false)
    private LocalDateTime agreedPrivacyAt;

    /** 가입 경로는 USER 고정 — SELLER/ADMIN은 시드 전용 (02 member) */
    public static Member signup(String email, String encodedPassword, String nickname,
                                Gender gender, LocalDate birthDate, LocalDateTime agreedAt) {
        Member member = new Member();
        member.email = email;
        member.password = encodedPassword;
        member.nickname = nickname;
        member.role = Role.USER;
        member.gender = gender;
        member.birthDate = birthDate;
        member.agreedTermsAt = agreedAt;
        member.agreedPrivacyAt = agreedAt;
        return member;
    }
}
