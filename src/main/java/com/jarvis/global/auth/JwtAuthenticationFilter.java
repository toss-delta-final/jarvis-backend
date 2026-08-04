package com.jarvis.global.auth;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 03 §3-1 — "토큰 없으면 통과, 있으면 파싱해 SecurityContext 세팅, 파싱 실패 시 실패 처리".
 * 부재/만료 구분은 request attribute로 넘기고 EntryPoint가 401 2종(AUTH_REQUIRED/AUTH_TOKEN_EXPIRED)으로 분기.
 * 여기서 즉시 401을 쓰지 않는 이유: A-3(로그아웃)은 AT가 만료된 채로 와도 RT 쿠키로 성공해야 한다(04).
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTH_ERROR_ATTRIBUTE = "jwtAuthError";
    public static final String AUTH_ERROR_EXPIRED = "EXPIRED";
    private final JwtProvider jwtProvider;
    private final AccessCookieManager accessCookieManager;
    private final TokenEpoch tokenEpoch;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        // AT는 HttpOnly 쿠키로만 받는다 (03 D3 — 2026-08-04). 구 Authorization 헤더 경로는 폐기 —
        // 두 경로를 함께 두면 "어느 쪽이 진실인지"가 흐려지고, 헤더는 JS가 채우는 자리라 XSS 표면이 남는다.
        String token = accessCookieManager.resolve(request).orElse(null);
        if (token != null && !token.isBlank()) {
            try {
                AuthUser authUser = jwtProvider.parseAccessToken(token);
                if (tokenEpoch.isRevoked(authUser.memberId(), authUser.issuedAt())) {
                    // 만료와 같은 취급 — FE가 A-4로 재발급을 시도하게 만든다(07 §3-2).
                    // AUTH_REQUIRED로 내보내면 로그인 화면으로 튕겨, 무효화가 정당한 사용자까지
                    // 로그아웃시킨다. RT가 없는 탈취자는 어차피 재발급에서 죽는다.
                    request.setAttribute(AUTH_ERROR_ATTRIBUTE, AUTH_ERROR_EXPIRED);
                    filterChain.doFilter(request, response);
                    return;
                }
                var authentication = new UsernamePasswordAuthenticationToken(
                        authUser, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + authUser.role().name())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (ExpiredJwtException e) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, AUTH_ERROR_EXPIRED);
            } catch (JwtException | IllegalArgumentException e) {
                // 무효 토큰은 미인증으로 취급 — 보호 경로면 EntryPoint가 AUTH_REQUIRED로 응답
            }
        }
        filterChain.doFilter(request, response);
    }
}
