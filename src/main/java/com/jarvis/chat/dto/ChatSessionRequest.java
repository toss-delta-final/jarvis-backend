package com.jarvis.chat.dto;

import com.jarvis.chat.ChatChannel;

/** CH-1 요청 (04 §6) — Body 선택. channel 미지정(또는 Body 없음) 시 SHOPPING 기본값 */
public record ChatSessionRequest(ChatChannel channel) {
}
