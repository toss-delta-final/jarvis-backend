package com.jarvis.member.dto;

/** A-1 — 성공 시 자동 로그인(토큰 발급). RT는 HttpOnly 쿠키로 나가 body에 없음 (03 D3) */
public record SignupResponse(String accessToken, MeResponse member) {
}
