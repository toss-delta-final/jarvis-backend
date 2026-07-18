package com.jarvis.member.dto;

/** A-2 — RT는 HttpOnly 쿠키로 나가 body에 없음 (03 D3) */
public record LoginResponse(String accessToken, MeResponse member) {
}
