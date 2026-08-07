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
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 03 §3-1 — "토큰 없으면 통과, 있으면 파싱해 SecurityContext 세팅, 파싱 실패 시 실패 처리".
 * 부재/만료 구분은 request attribute로 넘기고 EntryPoint가 401 2종(AUTH_REQUIRED/AUTH_TOKEN_EXPIRED)으로 분기.
 *
 * <p>예외는 {@link #IDENTITY_SENSITIVE_PREFIXES} 하나다 — 게스트 허용 경로인데도 만료를 여기서
 * 401로 끊는다(2026-08-07). 그 경로들은 신원에 따라 응답이 달라져서, 통과시키면 회원이 조용히
 * 게스트로 강등돼 <b>남의 빈 장바구니를 자기 것으로 본다</b>. 200이 나가니 FE는 재발급 기회조차 얻지 못한다.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTH_ERROR_ATTRIBUTE = "jwtAuthError";
    public static final String AUTH_ERROR_EXPIRED = "EXPIRED";

    /**
     * 만료 토큰을 게스트로 강등하면 <b>응답 내용이 달라지는</b> 경로 (노션 C-1~C-4·CH-1·CH-1b·CH-5·E-1).
     * 여기서만 401 AUTH_TOKEN_EXPIRED로 끊어 FE가 A-4 재발급 → 원 요청 재시도를 타게 한다.
     *
     * <p>공개 카탈로그(/api/products·/api/categories·/api/brands)는 <b>일부러 뺐다</b> — 로그인 여부와
     * 내용이 같아 게스트로 봐도 손해가 없는데, 쿠키 수명(14일)이 토큰 수명(30분)보다 길어 오래 방치한
     * 브라우저에는 만료 쿠키가 남아 있다. 거기에 401을 내면 RT까지 만료된 사람은 재발급도 실패해
     * <b>구경만 하러 온 사용자가 로그인 화면으로 튕긴다.</b>
     *
     * <p>/api/auth/**도 뺐다 — 만료 AT 쿠키는 Path=/라 재발급·로그아웃·재로그인 요청에 그대로 실려 온다.
     * 여기서 끊으면 A-4가 자기 자신을 막아 무한 루프가 되고(노션 A-4), 만료된 사용자는 다시 로그인할
     * 방법조차 없어진다.
     */
    private static final List<String> IDENTITY_SENSITIVE_PREFIXES =
            List.of("/api/cart", "/api/chat/", "/api/events");

    private final JwtProvider jwtProvider;
    private final AccessCookieManager accessCookieManager;
    private final TokenEpoch tokenEpoch;
    private final EnvelopeAuthenticationEntryPoint authenticationEntryPoint;

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
                    if (markExpired(request, response)) {
                        return;
                    }
                    filterChain.doFilter(request, response);
                    return;
                }
                var authentication = new UsernamePasswordAuthenticationToken(
                        authUser, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + authUser.role().name())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (ExpiredJwtException e) {
                if (markExpired(request, response)) {
                    return;
                }
            } catch (JwtException | IllegalArgumentException e) {
                // 무효 토큰은 미인증으로 취급 — 보호 경로면 EntryPoint가 AUTH_REQUIRED로 응답.
                // 변조는 재발급으로 고쳐지지 않으므로 만료와 달리 여기서 끊지 않는다.
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 만료를 기록하고, 신원이 갈리는 경로면 그 자리에서 401을 쓴다.
     *
     * @return true면 응답을 이미 썼다는 뜻 — 호출측은 체인을 더 태우지 않고 반환해야 한다
     */
    private boolean markExpired(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        request.setAttribute(AUTH_ERROR_ATTRIBUTE, AUTH_ERROR_EXPIRED);
        if (!identitySensitive(request)) {
            return false;
        }
        // EntryPoint를 그대로 재사용한다 — envelope 형식과 401 2종 분기가 한 곳에만 있어야
        // 보호 경로에서 나가는 401과 여기서 나가는 401이 갈라지지 않는다.
        authenticationEntryPoint.commence(request, response,
                new InsufficientAuthenticationException("access token expired"));
        return true;
    }

    private static boolean identitySensitive(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return IDENTITY_SENSITIVE_PREFIXES.stream().anyMatch(uri::startsWith);
    }
}
