package com.jarvis.member.dto;

/**
 * 서비스 내부 결과 — refreshToken은 원문(쿠키 세팅용, 컨트롤러 소관)이라 API 응답으로 직렬화하지 않는다.
 */
public record AuthResult(String accessToken, String refreshToken, MeResponse member) {
}
