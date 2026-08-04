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
 *
 * <p><b>쿠키 수명을 AT 수명(30분)이 아니라 RT 수명(14일)에 맞추는 이유</b> — 둘은 다른 것을 뜻한다.
 * 토큰의 진짜 수명은 JWT에 박힌 {@code exp}가 정하고 서버가 그걸로 판정한다. 쿠키의 {@code Max-Age}는
 * <b>브라우저가 이 값을 언제까지 들고 있을지</b>일 뿐이다.
 *
 * <p>둘을 같게 두면 <b>조용한 재발급이 죽는다.</b> 30분 정각에 브라우저가 쿠키를 스스로 지워버려
 * 서버가 만료된 토큰을 <b>보지 못하고</b>, 그래서 "재발급하라"({@code AUTH_TOKEN_EXPIRED})가 아니라
 * "로그인하라"({@code AUTH_REQUIRED})로 답하게 된다. RT가 14일 멀쩡히 남아 있는데도 FE는
 * 로그인 화면으로 보내버린다 — <b>30분마다 로그아웃</b>. 쿠키를 남겨둬야 서버가 만료를 인지하고
 * A-4로 유도할 수 있다.
 *
 * <p>만료된 값이 브라우저에 더 오래 남지만 <b>그 값은 아무 권한도 주지 않는다</b> — 인증 필요 경로에선
 * 401이고, 로그인·가입은 애초에 쿠키를 보지 않고 자격증명으로 판정한다.
 */
@Component
public class AccessCookieManager {

    public static final String COOKIE_NAME = "access_token";
    private static final String COOKIE_PATH = "/";

    private final Duration maxAge;
    private final boolean secure;

    public AccessCookieManager(JwtProperties properties,
                               @Value("${app.cookie.secure:true}") boolean secure) {
        // AT 수명이 아니라 RT 수명 — 위 Javadoc 참고. 여기를 accessTokenMinutes로 되돌리면
        // 30분마다 강제 로그아웃이 된다.
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
