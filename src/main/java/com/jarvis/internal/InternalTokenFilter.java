package com.jarvis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.global.response.ApiResponse;
import com.jarvis.global.response.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * /internal/** 서비스 토큰 검증 (03 D4) — FE 경유로는 절대 호출 불가 보장의 애플리케이션 층.
 * (네트워크 층 2겹 — nginx 미라우팅·포트 미노출 — 은 배포 형상 소관, 03 §1-1)
 * 토큰 미설정(빈 값)이면 전부 거부(fail-closed).
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";

    private final String internalToken;
    private final ObjectMapper objectMapper;

    public InternalTokenFilter(@Value("${app.internal.token:}") String internalToken,
                               ObjectMapper objectMapper) {
        this.internalToken = internalToken;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (internalToken == null || internalToken.isBlank() || provided == null
                || !MessageDigest.isEqual(internalToken.getBytes(StandardCharsets.UTF_8),
                        provided.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getWriter(),
                    ApiResponse.error(ErrorCode.INTERNAL_TOKEN_INVALID));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
