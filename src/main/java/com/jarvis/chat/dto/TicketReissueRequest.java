package com.jarvis.chat.dto;

import jakarta.validation.constraints.NotBlank;

/** CH-1b 요청 (04 §6) — 세션 유지, 티켓만 재발급 */
public record TicketReissueRequest(@NotBlank String sessionId) {
}
