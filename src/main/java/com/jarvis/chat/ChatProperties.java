package com.jarvis.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 채팅 세션 규약 (04 CH-1 — Redis TTL sliding).
 *
 * <p>{@code listTtlMinutes}는 세션과 <b>따로</b> 둔다(07 §2-1) — 세션 TTL의 근거는 대화 지속성이고
 * 추천 목록은 {@code listId} 노출 창이다. 값을 공유하면 세션을 늘릴 때 노출 창이 같이 늘어난다.
 */
@ConfigurationProperties(prefix = "app.chat")
public record ChatProperties(long sessionTtlMinutes, long listTtlMinutes) {
}
