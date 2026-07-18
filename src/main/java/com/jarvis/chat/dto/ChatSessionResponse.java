package com.jarvis.chat.dto;

/**
 * CH-1/CH-1b 응답 (04 §6) — ttlSeconds는 세션 TTL(초), ticketTtlSeconds는 티켓 수명(초).
 * FE는 llmSseUrl로 FastAPI에 직결
 */
public record ChatSessionResponse(String sessionId, long ttlSeconds, String streamTicket,
                                  long ticketTtlSeconds, String llmSseUrl) {
}
