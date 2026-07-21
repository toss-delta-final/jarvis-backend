package com.jarvis.member;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jarvis.global.auth.EnvelopeAccessDeniedHandler;
import com.jarvis.global.auth.GuestCookieManager;
import com.jarvis.global.auth.EnvelopeAuthenticationEntryPoint;
import com.jarvis.global.auth.JwtAuthenticationFilter;
import com.jarvis.global.auth.JwtProperties;
import com.jarvis.global.auth.JwtProvider;
import com.jarvis.global.auth.RefreshCookieManager;
import com.jarvis.global.config.SecurityConfig;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.member.dto.AuthResult;
import com.jarvis.member.dto.MeResponse;
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
        RefreshCookieManager.class, GuestCookieManager.class})
@TestPropertySource(properties = {
        "jwt.secret=test-hs256-secret-key-at-least-256-bits-long-0123456789",
        "jwt.access-token-minutes=30",
        "jwt.refresh-token-days=14"
})
class AuthControllerSecurityTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtProvider jwtProvider;
    @MockitoBean AuthService authService;

    private String validToken(Role role) {
        return jwtProvider.createAccessToken(1L, role);
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
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + expiredToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_EXPIRED"));
    }

    @Test
    @DisplayName("유효 토큰으로 /me — 200 + 내 정보")
    void me_withValidToken_200() throws Exception {
        when(authService.me(1L)).thenReturn(new MeResponse(1L, "user@test.com", "지현", Role.USER));

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + validToken(Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("user@test.com"))
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    @DisplayName("USER 토큰으로 SELLER 전용 경로 — 403 AUTH_FORBIDDEN envelope")
    void sellerPath_withUserToken_403() throws Exception {
        mockMvc.perform(get("/api/seller/summary")
                        .header("Authorization", "Bearer " + validToken(Role.USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    @DisplayName("SELLER 토큰으로 USER 전용 경로 — 403 (Phase 1 완료 조건)")
    void userOnlyPath_withSellerToken_403() throws Exception {
        mockMvc.perform(get("/api/orders")
                        .header("Authorization", "Bearer " + validToken(Role.SELLER)))
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
    @DisplayName("A-1 성공 — 200 + accessToken + RT HttpOnly 쿠키(Path=/api/auth)")
    void signup_success_setsRefreshCookie() throws Exception {
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
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.member.id").value(1))
                .andExpect(cookie().exists(RefreshCookieManager.COOKIE_NAME))
                .andExpect(cookie().httpOnly(RefreshCookieManager.COOKIE_NAME, true))
                .andExpect(cookie().path(RefreshCookieManager.COOKIE_NAME, "/api/auth"));
    }

    @Test
    @DisplayName("A-3 로그아웃 — RT 쿠키 없어도 200 + 쿠키 만료")
    void logout_withoutCookie_200() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(cookie().maxAge(RefreshCookieManager.COOKIE_NAME, 0));
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
