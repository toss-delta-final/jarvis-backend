package com.jarvis.chat.dto;

/** CH-1/CH-1b 응답 (04 §6) — expiresIn은 티켓 수명(초). FE는 llmSseUrl로 FastAPI에 직결 */
public record ChatSessionResponse(String sessionId, String streamTicket,
                                  String llmSseUrl, long expiresIn) {
}
