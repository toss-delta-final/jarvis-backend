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
 * AT HttpOnly 쿠키 (03 D3 — 2026-08-04 개정: 구 "AT는 응답 body + 메모리" 폐기).
 *
 * <p><b>왜 메모리에서 쿠키로 옮겼나</b>
 * <ul>
 *   <li><b>SSR 제약 해소</b> — AT가 브라우저 메모리에만 있으면 Next 서버가 볼 수 없어
 *       마이페이지·체크아웃을 서버에서 렌더할 수 없었다.</li>
 *   <li><b>XSS</b> — "메모리는 안전하다"는 절반만 맞다. 스크립트가 주입되면 JS 변수도 읽힌다.
 *       HttpOnly는 JS 접근을 원천 차단한다.</li>
 *   <li><b>로그아웃</b> — 서버가 쿠키를 만료시킬 수 있다. 메모리에 있을 땐 손댈 방법이 없었다.</li>
 * </ul>
 *
 * <p><b>RT와 속성이 다른 이유</b> — 의도된 비대칭이다.
 * <pre>
 * RT: Path=/api/auth + SameSite=Strict  재발급할 때만 실려 나가면 되므로 최대한 조인다
 * AT: Path=/         + SameSite=Lax     모든 API에 필요하고, 외부 링크로 진입해도 로그인이 유지돼야 한다
 * </pre>
 * {@code Strict}면 카카오톡·메일 링크로 들어온 첫 요청에 쿠키가 실리지 않아 로그아웃된 것처럼 보인다.
 * {@code Lax}는 <b>크로스사이트 POST·PUT·DELETE에는 쿠키를 싣지 않아</b> CSRF의 실질 위협을 막는다 —
 * 그래서 CSRF 토큰을 따로 두지 않는다(SecurityConfig 주석 참고). 전제는 <b>GET으로 상태를 바꾸는
 * API를 만들지 않는 것</b>이다.
 */
@Component
public class AccessCookieManager {

    public static final String COOKIE_NAME = "access_token";
    private static final String COOKIE_PATH = "/";

    private final Duration maxAge;
    private final boolean secure;

    public AccessCookieManager(JwtProperties properties,
                               @Value("${app.cookie.secure:true}") boolean secure) {
        this.maxAge = Duration.ofMinutes(properties.accessTokenMinutes());
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

    public void write(HttpServletResponse response, String accessToken) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(accessToken, maxAge).toString());
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
                .sameSite("Lax")
                .build();
    }
}
