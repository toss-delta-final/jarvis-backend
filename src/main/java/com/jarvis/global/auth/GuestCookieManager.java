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
 * guest_id HttpOnly 쿠키 (03 D3) — 발급 = 쿠키 세팅 + guest 행 INSERT가 한 동작으로,
 * INSERT는 도메인 서비스(GuestService) 소관이고 여기는 쿠키만 다룬다.
 *
 * <p>수명은 <b>2시간 sliding</b>이다(노션 E-1 2026-07-31 개정 · GUEST-LIFECYCLE). 게스트 신원은
 * "로그인과 로그인 사이의 익명 구간"이라 구간이 끝나면({@link #clear}) 은퇴하며, 구 30일 영속은
 * 공용 PC에서 앞 사람 구간이 뒤에 로그인한 계정에 붙는 창을 30일로 열어두는 문제가 있었다.
 */
@Component
public class GuestCookieManager {

    public static final String COOKIE_NAME = "guest_id";
    private static final Duration MAX_AGE = Duration.ofHours(2);

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
        response.addHeader(HttpHeaders.SET_COOKIE, build(guestId, MAX_AGE).toString());
    }

    /** 승계 시 반납 — 그 게스트 구간은 영구 종료된다. 이후 익명 활동은 새 guest_id로 쌓인다 */
    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build("", Duration.ZERO).toString());
    }

    private ResponseCookie build(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .maxAge(maxAge)
                .sameSite("Lax")
                .build();
    }
}
