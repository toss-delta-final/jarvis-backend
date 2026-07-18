package com.jarvis.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jarvis.cart.CartService;
import com.jarvis.chat.ChatSessionService;
import com.jarvis.global.auth.JwtProperties;
import com.jarvis.global.auth.JwtProvider;
import com.jarvis.global.auth.TokenHasher;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.member.dto.AuthResult;
import com.jarvis.member.dto.LoginRequest;
import com.jarvis.member.dto.SignupRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String SECRET = "test-hs256-secret-key-at-least-256-bits-long-0123456789";
    private static final String IP = "203.0.113.9";

    @Mock MemberRepository memberRepository;
    @Mock GuestRepository guestRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock AccountEventLogger accountEventLogger;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock CartService cartService;
    @Mock ChatSessionService chatSessionService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final JwtProperties jwtProperties = new JwtProperties(SECRET, 30, 14);
    private final JwtProvider jwtProvider = new JwtProvider(jwtProperties);

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(memberRepository, guestRepository, refreshTokenRepository,
                accountEventLogger, passwordEncoder, jwtProvider, jwtProperties, jdbcTemplate,
                cartService, chatSessionService);
    }

    private SignupRequest signupRequest(String guestId) {
        return new SignupRequest("user@test.com", "password1", "지현", Gender.FEMALE,
                LocalDate.of(1999, 1, 1), true, true, guestId);
    }

    private Member memberFixture(Long id) {
        Member member = Member.signup("user@test.com", passwordEncoder.encode("password1"),
                "지현", Gender.FEMALE, LocalDate.of(1999, 1, 1), LocalDateTime.now());
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    @Nested
    class Signup {

        @Test
        @DisplayName("중복 이메일이면 409 MEMBER_EMAIL_DUPLICATE")
        void duplicateEmail_throws409() {
            when(memberRepository.existsByEmail("user@test.com")).thenReturn(true);

            assertThatThrownBy(() -> authService.signup(signupRequest(null), IP))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.MEMBER_EMAIL_DUPLICATE);
            verify(memberRepository, never()).save(any());
        }

        @Test
        @DisplayName("가입 성공 — BCrypt 저장, USER 롤, AT 파싱 가능, RT 해시 저장, SIGNUP 로그")
        void success_issuesTokensAndLogs() {
            when(memberRepository.existsByEmail("user@test.com")).thenReturn(false);
            when(memberRepository.save(any(Member.class))).thenAnswer(inv -> {
                Member m = inv.getArgument(0);
                ReflectionTestUtils.setField(m, "id", 1L);
                return m;
            });

            AuthResult result = authService.signup(signupRequest(null), IP);

            ArgumentCaptor<Member> memberCaptor = ArgumentCaptor.forClass(Member.class);
            verify(memberRepository).save(memberCaptor.capture());
            Member saved = memberCaptor.getValue();
            assertThat(saved.getPassword()).isNotEqualTo("password1");
            assertThat(passwordEncoder.matches("password1", saved.getPassword())).isTrue();
            assertThat(saved.getRole()).isEqualTo(Role.USER);

            assertThat(jwtProvider.parseAccessToken(result.accessToken()).memberId()).isEqualTo(1L);

            ArgumentCaptor<RefreshToken> rtCaptor = ArgumentCaptor.forClass(RefreshToken.class);
            verify(refreshTokenRepository).save(rtCaptor.capture());
            assertThat(rtCaptor.getValue().getTokenHash())
                    .isEqualTo(TokenHasher.sha256Hex(result.refreshToken()));

            verify(accountEventLogger).log(1L, AccountEventType.SIGNUP, IP);
            assertThat(result.member().email()).isEqualTo("user@test.com");
        }

        @Test
        @DisplayName("guestId가 오면 게스트 승계 — converted_member_id 기록 + behavior_events 백필")
        void withGuestId_convertsGuest() {
            String guestId = "11111111-1111-1111-1111-111111111111";
            Guest guest = Guest.issue(guestId);
            when(memberRepository.existsByEmail("user@test.com")).thenReturn(false);
            when(memberRepository.save(any(Member.class))).thenAnswer(inv -> {
                Member m = inv.getArgument(0);
                ReflectionTestUtils.setField(m, "id", 1L);
                return m;
            });
            when(guestRepository.findById(guestId)).thenReturn(Optional.of(guest));

            authService.signup(signupRequest(guestId), IP);

            assertThat(guest.getConvertedMemberId()).isEqualTo(1L);
            verify(jdbcTemplate).update(
                    eq("UPDATE behavior_events SET member_id = ? WHERE guest_id = ? AND member_id IS NULL"),
                    eq(1L), eq(guestId));
        }
    }

    @Nested
    class Login {

        @Test
        @DisplayName("없는 이메일 — 통일 401 AUTH_LOGIN_FAILED + LOGIN_FAIL(member_id NULL) 로그")
        void unknownEmail_throws401AndLogsFail() {
            when(memberRepository.findByEmail("user@test.com")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(
                    new LoginRequest("user@test.com", "password1", null), IP))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.AUTH_LOGIN_FAILED);

            verify(accountEventLogger).log(null, AccountEventType.LOGIN_FAIL, IP);
        }

        @Test
        @DisplayName("비밀번호 불일치 — 같은 401 + LOGIN_FAIL(member_id) 로그")
        void wrongPassword_throws401AndLogsFail() {
            when(memberRepository.findByEmail("user@test.com"))
                    .thenReturn(Optional.of(memberFixture(1L)));

            assertThatThrownBy(() -> authService.login(
                    new LoginRequest("user@test.com", "wrong-pw1", null), IP))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.AUTH_LOGIN_FAILED);

            verify(accountEventLogger).log(1L, AccountEventType.LOGIN_FAIL, IP);
        }

        @Test
        @DisplayName("로그인 성공 — 토큰 발급 + LOGIN_SUCCESS 로그")
        void success_issuesTokensAndLogs() {
            when(memberRepository.findByEmail("user@test.com"))
                    .thenReturn(Optional.of(memberFixture(1L)));

            AuthResult result = authService.login(
                    new LoginRequest("user@test.com", "password1", null), IP);

            assertThat(jwtProvider.parseAccessToken(result.accessToken()).memberId()).isEqualTo(1L);
            verify(refreshTokenRepository).save(any(RefreshToken.class));
            verify(accountEventLogger).log(1L, AccountEventType.LOGIN_SUCCESS, IP);
        }
    }

    @Nested
    class Refresh {

        @Test
        @DisplayName("유효한 RT — 새 AT 발급 + RT 교체(기존 삭제, 새 해시 저장)")
        void validToken_rotates() {
            String raw = "raw-refresh-token";
            RefreshToken stored = RefreshToken.issue(1L, TokenHasher.sha256Hex(raw),
                    LocalDateTime.now().plusDays(7));
            when(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(raw)))
                    .thenReturn(Optional.of(stored));
            when(refreshTokenRepository.deleteByTokenHash(TokenHasher.sha256Hex(raw))).thenReturn(1);
            when(memberRepository.findById(1L)).thenReturn(Optional.of(memberFixture(1L)));

            AuthResult result = authService.refresh(raw);

            assertThat(jwtProvider.parseAccessToken(result.accessToken()).memberId()).isEqualTo(1L);
            verify(refreshTokenRepository).deleteByTokenHash(TokenHasher.sha256Hex(raw));
            ArgumentCaptor<RefreshToken> rtCaptor = ArgumentCaptor.forClass(RefreshToken.class);
            verify(refreshTokenRepository).save(rtCaptor.capture());
            assertThat(rtCaptor.getValue().getTokenHash())
                    .isEqualTo(TokenHasher.sha256Hex(result.refreshToken()));
            assertThat(result.refreshToken()).isNotEqualTo(raw);
        }

        @Test
        @DisplayName("모르는 RT — 401 AUTH_REQUIRED (재로그인 유도)")
        void unknownToken_throws401() {
            when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.refresh("unknown"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.AUTH_REQUIRED);
        }

        @Test
        @DisplayName("만료된 RT — row 삭제 + 401 AUTH_REQUIRED")
        void expiredToken_deletesAndThrows401() {
            String raw = "expired-refresh-token";
            RefreshToken stored = RefreshToken.issue(1L, TokenHasher.sha256Hex(raw),
                    LocalDateTime.now().minusMinutes(1));
            when(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(raw)))
                    .thenReturn(Optional.of(stored));

            assertThatThrownBy(() -> authService.refresh(raw))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.AUTH_REQUIRED);
            verify(refreshTokenRepository).deleteByTokenHash(TokenHasher.sha256Hex(raw));
        }

        @Test
        @DisplayName("동시 refresh 경합 — 조건부 삭제가 0건이면 발급 없이 401 (회전 단일성)")
        void concurrentRotation_losesRace_throws401() {
            String raw = "raw-refresh-token";
            RefreshToken stored = RefreshToken.issue(1L, TokenHasher.sha256Hex(raw),
                    LocalDateTime.now().plusDays(7));
            when(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(raw)))
                    .thenReturn(Optional.of(stored));
            // 다른 스레드가 먼저 소비 → 이 요청의 삭제는 0건
            when(refreshTokenRepository.deleteByTokenHash(TokenHasher.sha256Hex(raw))).thenReturn(0);

            assertThatThrownBy(() -> authService.refresh(raw))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.AUTH_REQUIRED);
            verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
        }

        @Test
        @DisplayName("RT 쿠키 없음 — 401 AUTH_REQUIRED")
        void nullToken_throws401() {
            assertThatThrownBy(() -> authService.refresh(null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.AUTH_REQUIRED);
        }
    }

    @Nested
    class Logout {

        @Test
        @DisplayName("RT가 있으면 row 삭제 + LOGOUT 로그")
        void withToken_deletesAndLogs() {
            String raw = "raw-refresh-token";
            RefreshToken stored = RefreshToken.issue(1L, TokenHasher.sha256Hex(raw),
                    LocalDateTime.now().plusDays(7));
            when(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(raw)))
                    .thenReturn(Optional.of(stored));

            authService.logout(raw, IP);

            verify(refreshTokenRepository).delete(stored);
            verify(accountEventLogger).log(1L, AccountEventType.LOGOUT, IP);
        }

        @Test
        @DisplayName("RT 쿠키가 없어도 예외 없이 성공 (A-3 — AT 만료 상태에서도 로그아웃 가능)")
        void withoutToken_noException() {
            authService.logout(null, IP);

            verify(refreshTokenRepository, never()).delete(any());
        }
    }

    @Nested
    class Me {

        @Test
        @DisplayName("존재하는 회원 — id/email/nickname/role 반환")
        void existingMember_returnsInfo() {
            when(memberRepository.findById(1L)).thenReturn(Optional.of(memberFixture(1L)));

            var me = authService.me(1L);

            assertThat(me.id()).isEqualTo(1L);
            assertThat(me.email()).isEqualTo("user@test.com");
            assertThat(me.role()).isEqualTo(Role.USER);
        }
    }
}
