package com.jarvis.global.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jarvis.member.Role;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtProviderTest {

    private static final String SECRET = "test-hs256-secret-key-at-least-256-bits-long-0123456789";

    private JwtProvider provider(long accessMinutes) {
        return new JwtProvider(new JwtProperties(SECRET, accessMinutes, 14));
    }

    @Test
    @DisplayName("AT 생성 후 파싱하면 memberId와 role이 복원된다")
    void createAndParse_roundTrip() {
        JwtProvider provider = provider(30);

        String token = provider.createAccessToken(7L, Role.SELLER);
        AuthUser user = provider.parseAccessToken(token);

        assertThat(user.memberId()).isEqualTo(7L);
        assertThat(user.role()).isEqualTo(Role.SELLER);
    }

    @Test
    @DisplayName("만료된 AT는 ExpiredJwtException을 던진다")
    void parse_expiredToken_throwsExpired() {
        JwtProvider provider = provider(-1);

        String token = provider.createAccessToken(1L, Role.USER);

        assertThatThrownBy(() -> provider(30).parseAccessToken(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("형식이 깨진 토큰은 JwtException을 던진다")
    void parse_malformedToken_throwsJwtException() {
        assertThatThrownBy(() -> provider(30).parseAccessToken("not-a-jwt"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("다른 키로 서명된 토큰은 JwtException을 던진다")
    void parse_wrongSignature_throwsJwtException() {
        JwtProvider other = new JwtProvider(
                new JwtProperties("another-secret-key-that-is-also-long-enough-9876543210", 30, 14));
        String token = other.createAccessToken(1L, Role.USER);

        assertThatThrownBy(() -> provider(30).parseAccessToken(token))
                .isInstanceOf(JwtException.class);
    }
}
