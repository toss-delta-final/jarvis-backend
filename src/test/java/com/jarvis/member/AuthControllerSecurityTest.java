package com.jarvis.member;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jarvis.global.auth.AccessCookieManager;
import com.jarvis.global.auth.EnvelopeAccessDeniedHandler;
import com.jarvis.global.auth.GuestCookieManager;
import com.jarvis.global.auth.EnvelopeAuthenticationEntryPoint;
import com.jarvis.global.auth.ClientIp;
import com.jarvis.global.auth.JwtAuthenticationFilter;
import com.jarvis.global.auth.JwtProperties;
import com.jarvis.global.auth.JwtProvider;
import com.jarvis.global.auth.RefreshCookieManager;
import com.jarvis.global.auth.TokenEpoch;
import com.jarvis.global.config.SecurityConfig;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.member.dto.AuthResult;
import com.jarvis.member.dto.MeResponse;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtProvider.class,
        EnvelopeAuthenticationEntryPoint.class, EnvelopeAccessDeniedHandler.class,
        RefreshCookieManager.class, GuestCookieManager.class, ClientIp.class,
        AccessCookieManager.class})
@TestPropertySource(properties = {
        "jwt.secret=test-hs256-secret-key-at-least-256-bits-long-0123456789",
        "jwt.access-token-minutes=30",
        "jwt.refresh-token-days=14",
        "app.client-ip.forwarded-depth=2"
})
class AuthControllerSecurityTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtProvider jwtProvider;
    @MockitoBean AuthService authService;
    /** 무효화 마커는 Redis라 목으로 둔다 — 기본값 false(무효화 없음)로 통과 */
    @MockitoBean TokenEpoch tokenEpoch;

    private String validToken(Role role) {
        return jwtProvider.createAccessToken(1L, role);
    }

    /** AT는 이제 HttpOnly 쿠키로만 들어온다 (03 D3) — 헤더 경로는 폐기됐다 */
    private Cookie accessCookie(String token) {
        return new Cookie(AccessCookieManager.COOKIE_NAME, token);
    }

    private String expiredToken() {
        JwtProvider expiredIssuer = new JwtProvider(
                new JwtProperties("test-hs256-secret-key-at-least-256-bits-long-0123456789", -1, 14));
        return expiredIssuer.createAccessToken(1L, Role.USER);
    }

    @Test
    @DisplayName("토큰 없이 /me — 401 AUTH_REQUIRED envelope")
    void me_withoutToken_401AuthRequired() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }

    @Test
    @DisplayName("만료 토큰으로 /me — 401 AUTH_TOKEN_EXPIRED envelope (401 2종 분리)")
    void me_withExpiredToken_401TokenExpired() throws Exception {
        mockMvc.perform(get("/api/auth/me").cookie(accessCookie(expiredToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_EXPIRED"));
    }

    @Test
    @DisplayName("무효화된 토큰으로 /me — 401 AUTH_TOKEN_EXPIRED (AUTH_REQUIRED 아님)")
    void me_withRevokedToken_401TokenExpired() throws Exception {
        // 서명·만료는 멀쩡하지만 로그아웃 등으로 무효화된 토큰 (07 §3-2)
        when(tokenEpoch.isRevoked(eq(1L), any())).thenReturn(true);

        // AUTH_REQUIRED로 내보내면 FE가 로그인 화면으로 튕겨, 정당한 사용자까지 로그아웃된다.
        // EXPIRED여야 A-4 재발급을 시도해 자동 복구된다 — RT 없는 탈취자는 거기서 죽는다
        mockMvc.perform(get("/api/auth/me").cookie(accessCookie(validToken(Role.USER))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_EXPIRED"));
    }

    @Test
    @DisplayName("유효 토큰으로 /me — 200 + 내 정보")
    void me_withValidToken_200() throws Exception {
        when(authService.me(1L)).thenReturn(new MeResponse(1L, "user@test.com", "지현", Role.USER));

        mockMvc.perform(get("/api/auth/me").cookie(accessCookie(validToken(Role.USER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("user@test.com"))
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    @DisplayName("USER 토큰으로 SELLER 전용 경로 — 403 AUTH_FORBIDDEN envelope")
    void sellerPath_withUserToken_403() throws Exception {
        mockMvc.perform(get("/api/seller/summary").cookie(accessCookie(validToken(Role.USER))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    @DisplayName("SELLER 토큰으로 USER 전용 경로 — 403 (Phase 1 완료 조건)")
    void userOnlyPath_withSellerToken_403() throws Exception {
        mockMvc.perform(get("/api/orders").cookie(accessCookie(validToken(Role.SELLER))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    @DisplayName("A-1 검증 실패 — 400 VALIDATION_ERROR + fields[]")
    void signup_invalidBody_400WithFields() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","password":"short","nickname":"",
                                 "gender":"MALE","birthDate":"1999-01-01",
                                 "agreeTerms":true,"agreePrivacy":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fields").isArray());
    }

    @Test
    @DisplayName("A-1 성공 — 200 + AT·RT 두 쿠키 (body에는 토큰이 없다)")
    void signup_success_setsBothCookies() throws Exception {
        when(authService.signup(any(), anyString(), any())).thenReturn(new AuthResult(
                "access-token", "refresh-token-raw",
                new MeResponse(1L, "user@test.com", "지현", Role.USER)));

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@test.com","password":"password1","nickname":"지현",
                                 "gender":"FEMALE","birthDate":"1999-01-01",
                                 "agreeTerms":true,"agreePrivacy":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                // isString이 있어야 타입을 실제로 본다 — value(1)만으로는 JsonPath가 "1"을 1로
                // 강제 변환해 통과시켜서, id가 숫자로 되돌아가도 이 단언은 못 잡는다(2026-08-06)
                .andExpect(jsonPath("$.data.member.id").isString())
                .andExpect(jsonPath("$.data.member.id").value("1"))
                // AT는 전 경로에 실려야 한다 — /api/products 같은 일반 API도 인증을 읽는다
                .andExpect(cookie().value(AccessCookieManager.COOKIE_NAME, "access-token"))
                .andExpect(cookie().httpOnly(AccessCookieManager.COOKIE_NAME, true))
                .andExpect(cookie().path(AccessCookieManager.COOKIE_NAME, "/"))
                // 쿠키 수명은 AT(30분)가 아니라 RT(14일) 기준이어야 한다 — 30분으로 두면 브라우저가
                // 정각에 쿠키를 지워 서버가 만료를 못 보고, AUTH_TOKEN_EXPIRED 대신 AUTH_REQUIRED가
                // 나가 조용한 재발급이 죽는다(30분마다 강제 로그아웃)
                .andExpect(cookie().maxAge(AccessCookieManager.COOKIE_NAME, (int) Duration.ofDays(14).toSeconds()))
                .andExpect(cookie().exists(RefreshCookieManager.COOKIE_NAME))
                .andExpect(cookie().httpOnly(RefreshCookieManager.COOKIE_NAME, true))
                .andExpect(cookie().path(RefreshCookieManager.COOKIE_NAME, "/api/auth"));
    }

    @Test
    @DisplayName("A-3 로그아웃 — 쿠키 없어도 200 + AT·RT 둘 다 만료")
    void logout_withoutCookie_200() throws Exception {
        // AT를 안 지우면 RT만 사라져 AT 만료(30분)까지 로그인 상태가 남는다
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(cookie().maxAge(AccessCookieManager.COOKIE_NAME, 0))
                .andExpect(cookie().maxAge(RefreshCookieManager.COOKIE_NAME, 0));
    }

    @Test
    @DisplayName("A-4 refresh 성공 — AT 쿠키를 새 값으로 덮어쓴다 (응답 body는 비어 있다)")
    void refresh_success_rotatesAccessCookie() throws Exception {
        when(authService.refresh(anyString())).thenReturn(new AuthResult(
                "new-access-token", "new-refresh-token",
                new MeResponse(1L, "user@test.com", "지현", Role.USER)));

        // AT가 갱신되지 않으면 FE는 만료될 때마다 refresh를 성공시키고도 계속 401을 받는다
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie(RefreshCookieManager.COOKIE_NAME, "old-refresh-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(cookie().value(AccessCookieManager.COOKIE_NAME, "new-access-token"))
                .andExpect(cookie().value(RefreshCookieManager.COOKIE_NAME, "new-refresh-token"));
    }

    @Test
    @DisplayName("A-4 RT 쿠키 없이 refresh — 401 AUTH_REQUIRED envelope")
    void refresh_withoutCookie_401() throws Exception {
        when(authService.refresh(isNull()))
                .thenThrow(new BusinessException(ErrorCode.AUTH_REQUIRED));

        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }
}
