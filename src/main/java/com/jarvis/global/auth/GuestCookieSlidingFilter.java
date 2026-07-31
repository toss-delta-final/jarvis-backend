package com.jarvis.global.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * guest_id 쿠키를 활동마다 2시간으로 밀어준다(sliding — 노션 E-1 2026-07-31 · GUEST-LIFECYCLE).
 * 게스트 신원은 "로그인과 로그인 사이의 익명 구간"이라, 쓰는 동안 살아 있고 2시간 놀면 만료돼야 한다.
 *
 * <p>연장만 하고 발급은 하지 않는다 — 발급은 쿠키 세팅과 guest 행 INSERT가 한 동작이라 도메인 서비스
 * 소관이다(03 D3). 핸들러보다 <b>먼저</b> 쓴다: 응답 본문이 나간 뒤에는 헤더를 못 붙이기 때문이고,
 * 승계로 쿠키를 반납하는 경로는 아래 {@link #shouldNotFilter}로 아예 제외해 충돌을 만들지 않는다.
 */
@Component
@RequiredArgsConstructor
public class GuestCookieSlidingFilter extends OncePerRequestFilter {

    private final GuestCookieManager guestCookieManager;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        guestCookieManager.resolve(request)
                .ifPresent(guestId -> guestCookieManager.write(response, guestId));
        chain.doFilter(request, response);
    }

    /**
     * /api/ 밖(정적·헬스체크)은 매 요청 Set-Cookie를 붙일 이유가 없고,
     * /api/auth/는 승계가 쿠키를 반납하는 자리라 연장하면 안 된다.
     */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/") || uri.startsWith("/api/auth/");
    }
}
