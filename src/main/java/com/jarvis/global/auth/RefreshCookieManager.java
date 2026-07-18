package com.jarvis.global.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * RT HttpOnly 쿠키 (03 D3) — Path=/api/auth로 전송 범위 최소화, SameSite=Strict로 CSRF 표면 봉쇄 (03 §3-1).
 * Secure 속성으로 평문 HTTP 전송 차단(운영 HTTPS) — 14일 장수명 RT의 네트워크 탈취 방어.
 */
@Component
public class RefreshCookieManager {

    public static final String COOKIE_NAME = "refresh_token";
    private static final String COOKIE_PATH = "/api/auth";

    private final Duration maxAge;
    private final boolean secure;

    public RefreshCookieManager(JwtProperties properties,
                                @Value("${app.cookie.secure:true}") boolean secure) {
        this.maxAge = Duration.ofDays(properties.refreshTokenDays());
        this.secure = secure;
    }

    public Optional<String> resolve(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }

    public void write(HttpServletResponse response, String refreshToken) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(refreshToken, maxAge).toString());
    }

    public void expire(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build("", Duration.ZERO).toString());
    }

    private ResponseCookie build(String value, Duration age) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secure)
                .path(COOKIE_PATH)
                .maxAge(age)
                .sameSite("Strict")
                .build();
    }
}
