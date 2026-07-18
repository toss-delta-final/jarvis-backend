package com.jarvis.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 스트림 티켓 RS256 키 (03 §5) — private key는 base64(PKCS#8 DER), Spring만 보관·회전 */
@ConfigurationProperties(prefix = "app.stream-ticket")
public record StreamTicketProperties(String privateKey, String kid, long ttlSeconds) {
}
