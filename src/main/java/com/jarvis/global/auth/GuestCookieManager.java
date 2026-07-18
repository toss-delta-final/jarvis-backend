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
 * guest_id HttpOnly 쿠키 (03 D3) — Max-Age 30일 명시 필수(세션 쿠키면 브라우저 닫는 순간 게스트 장바구니 증발).
 * 발급 = 쿠키 세팅 + guest 행 INSERT가 한 동작 — INSERT는 도메인 서비스(GuestService) 소관, 여기는 쿠키만.
 */
@Component
public class GuestCookieManager {

    public static final String COOKIE_NAME = "guest_id";
    private static final Duration MAX_AGE = Duration.ofDays(30);

    private final boolean secure;

    public GuestCookieManager(@Value("${app.cookie.secure:true}") boolean secure) {
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

    public void write(HttpServletResponse response, String guestId) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, guestId)
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .maxAge(MAX_AGE)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
