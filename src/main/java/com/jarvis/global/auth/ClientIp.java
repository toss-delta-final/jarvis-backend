package com.jarvis.global.auth;

import jakarta.servlet.http.HttpServletRequest;

/** account_event_logs.ip_address용 — nginx 뒤에서는 X-Forwarded-For 첫 값이 실제 클라이언트 (03 §1-1) */
public final class ClientIp {

    private ClientIp() {
    }

    public static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
