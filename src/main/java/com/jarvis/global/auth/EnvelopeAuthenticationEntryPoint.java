package com.jarvis.global.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.global.response.ApiResponse;
import com.jarvis.global.response.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 필터 체인에서 터진 401도 envelope로 (03 §3-1 — GlobalExceptionHandler 바깥이라 직접 쓴다).
 * JWT 필터가 남긴 attribute로 부재(AUTH_REQUIRED)/만료(AUTH_TOKEN_EXPIRED)를 분기 (03 D2).
 */
@Component
@RequiredArgsConstructor
public class EnvelopeAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        ErrorCode code = JwtAuthenticationFilter.AUTH_ERROR_EXPIRED
                .equals(request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE))
                ? ErrorCode.AUTH_TOKEN_EXPIRED
                : ErrorCode.AUTH_REQUIRED;
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code));
    }
}
