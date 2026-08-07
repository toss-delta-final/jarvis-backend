package com.jarvis.global.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.member.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 만료 토큰을 게스트로 강등할지 401로 끊을지는 <b>경로가 정한다</b>(2026-08-07, 03 §3-1).
 * 이 갈림이 무너지면 증상이 조용해서 잡기 어렵다 — 장바구니가 이유 없이 비어 보이거나(강등),
 * 구경만 하러 온 사용자가 로그인 화면으로 튕긴다(과잉 차단). 그래서 양쪽을 다 고정해 둔다.
 */
class JwtAuthenticationFilterTest {

    private static final String SECRET = "test-hs256-secret-key-at-least-256-bits-long-0123456789";

    private JwtAuthenticationFilter filter;
    private TokenEpoch tokenEpoch;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties(SECRET, 30, 14);
        tokenEpoch = mock(TokenEpoch.class);
        when(tokenEpoch.isRevoked(anyLong(), any())).thenReturn(false);
        filter = new JwtAuthenticationFilter(new JwtProvider(properties),
                new AccessCookieManager(properties, true), tokenEpoch,
                new EnvelopeAuthenticationEntryPoint(new ObjectMapper()));
    }

    /** 만료 토큰 — 서명은 멀쩡하고 exp만 지났다 */
    private static String expiredToken() {
        return new JwtProvider(new JwtProperties(SECRET, -1, 14)).createAccessToken(1L, Role.USER);
    }

    private static String validToken() {
        return new JwtProvider(new JwtProperties(SECRET, 30, 14)).createAccessToken(1L, Role.USER);
    }

    private MockHttpServletResponse dispatch(String uri, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        if (token != null) {
            request.setCookies(new Cookie(AccessCookieManager.COOKIE_NAME, token));
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    @DisplayName("만료 쿠키 + 장바구니 — 401 AUTH_TOKEN_EXPIRED로 끊는다")
    void expired_onCart_401() throws Exception {
        // 통과시키면 회원이 게스트로 강등돼 남의 빈 장바구니를 자기 것으로 본다.
        // 200이 나가면 FE가 A-4 재발급을 시도할 계기 자체가 없다.
        MockHttpServletResponse response = dispatch("/api/cart", expiredToken());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("AUTH_TOKEN_EXPIRED");
    }

    @Test
    @DisplayName("만료 쿠키 + 채팅·이벤트 — 같은 이유로 401")
    void expired_onChatAndEvents_401() throws Exception {
        assertThat(dispatch("/api/chat/sessions", expiredToken()).getStatus()).isEqualTo(401);
        assertThat(dispatch("/api/events", expiredToken()).getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("만료 쿠키 + 공개 카탈로그 — 게스트로 통과시킨다(401 아님)")
    void expired_onPublicCatalog_passesThrough() throws Exception {
        // 쿠키 수명(14일) > 토큰 수명(30분)이라 오래 방치한 브라우저엔 만료 쿠키가 남아 있다.
        // 여기서 401을 내면 RT까지 만료된 사람은 재발급도 실패해, 구경만 하러 온 사용자가
        // 로그인 화면으로 튕긴다. 로그인 여부로 내용이 같은 경로라 강등돼도 손해가 없다.
        assertThat(dispatch("/api/products/popular", expiredToken()).getStatus()).isEqualTo(200);
        assertThat(dispatch("/api/categories", expiredToken()).getStatus()).isEqualTo(200);
        assertThat(dispatch("/api/brands/3", expiredToken()).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("만료 쿠키 + /api/auth/** — 통과시킨다. 끊으면 재발급·재로그인이 막힌다")
    void expired_onAuthPaths_passesThrough() throws Exception {
        // 만료 AT 쿠키는 Path=/라 이 요청들에도 그대로 실려 온다. 여기서 401을 내면
        // A-4가 자기 자신을 막아 무한 루프가 되고, 만료된 사용자는 다시 로그인할 길이 없어진다.
        assertThat(dispatch("/api/auth/refresh", expiredToken()).getStatus()).isEqualTo(200);
        assertThat(dispatch("/api/auth/login", expiredToken()).getStatus()).isEqualTo(200);
        assertThat(dispatch("/api/auth/logout", expiredToken()).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("에폭 무효화된 토큰 + 장바구니 — 만료와 같은 취급으로 401")
    void revoked_onCart_401() throws Exception {
        when(tokenEpoch.isRevoked(anyLong(), any())).thenReturn(true);

        MockHttpServletResponse response = dispatch("/api/cart", validToken());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("AUTH_TOKEN_EXPIRED");
    }

    @Test
    @DisplayName("변조 쿠키 + 장바구니 — 401이 아니라 게스트로 통과")
    void tampered_onCart_passesThrough() throws Exception {
        // 변조는 재발급으로 고쳐지지 않아 401을 내도 사용자가 할 수 있는 게 없다.
        assertThat(dispatch("/api/cart", "not-a-jwt").getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("쿠키 없음 — 어느 경로든 필터가 손대지 않는다(게스트 정상 경로)")
    void noCookie_passesThrough() throws Exception {
        assertThat(dispatch("/api/cart", null).getStatus()).isEqualTo(200);
        assertThat(dispatch("/api/products/popular", null).getStatus()).isEqualTo(200);
    }
}
