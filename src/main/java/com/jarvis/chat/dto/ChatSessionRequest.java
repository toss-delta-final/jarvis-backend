package com.jarvis.chat.dto;

import com.jarvis.chat.ChatChannel;
import jakarta.validation.constraints.NotNull;

/** CH-1 요청 (04 §6) — channel: SHOPPING | CS */
public record ChatSessionRequest(@NotNull ChatChannel channel) {
}
