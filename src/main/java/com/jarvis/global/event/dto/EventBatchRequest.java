package com.jarvis.global.event.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * E-1 (04 §8) — FE가 버퍼(10건 or 5초)로 묶어 전송.
 * body의 신원 주장은 없음(있어도 무시) — member/guest는 서버가 JWT·쿠키에서 주입 (02 D31).
 */
public record EventBatchRequest(@NotEmpty @Size(max = 100) @Valid List<EventItem> events) {

    public record EventItem(
            @Size(max = 36) String id,          // client_event_id (UUID) — 중복 차단 키 (02 D35)
            @NotBlank @Size(max = 64) String sessionKey,
            @NotBlank @Size(max = 30) String eventType,
            Long productId,
            Map<String, Object> properties,
            String occurredAt                    // FE 발생 시각 — 저장 안 함(created_at은 서버 수신 시각)
    ) {
    }
}
