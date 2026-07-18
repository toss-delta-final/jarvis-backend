package com.jarvis.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 채팅 세션 규약 (04 CH-1 — Redis TTL 10분 sliding) */
@ConfigurationProperties(prefix = "app.chat")
public record ChatProperties(long sessionTtlMinutes) {
}
