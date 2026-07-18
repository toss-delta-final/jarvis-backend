package com.jarvis.global.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 03 D3 — AT 30분 / RT 14일, 시크릿은 환경변수 (03 §5) */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret, long accessTokenMinutes, long refreshTokenDays) {
}
