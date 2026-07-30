package com.jarvis.global.event.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * E-1 (04 §8) — FE가 버퍼(10건 or 5초)로 묶어 전송.
 * body의 신원 주장은 없음(있어도 무시) — member/guest는 서버가 JWT·쿠키에서 주입 (02 D31).
 */
public record EventBatchRequest(@NotEmpty @Size(max = 100) @Valid List<EventItem> events) {

    public record EventItem(
            /**
             * client_event_id (UUID) — 중복 차단 키 (02 D35). **필수**(노션 E-1 2026-07-28 확정):
             * 비면 DB UNIQUE가 아무 일도 하지 않아 재전송이 그대로 쌓인다. 이 검증이 실패하면
             * 배치 전체가 400 — SDK는 상한을 지켜 분할 전송해야 한다.
             */
            @NotBlank @Size(max = 36) String id,
            @NotBlank @Size(max = 64) String sessionKey,
            @NotBlank @Size(max = 30) String eventType,
            Long productId,
            Integer schemaVersion,               // 전용 컬럼 없이 properties에 담아 저장 (02 D38)
            Map<String, Object> properties,
            /**
             * FE 발생 시각 — **UTC ISO-8601(Z) 또는 offset 포함**(노션 E-1). 타임존 없는 로컬
             * 시각은 정렬이 깨지므로 OffsetDateTime으로 받아 역직렬화 단계에서 거절한다.
             * 서버는 이 값을 occurred_at에, 수신 시각을 created_at에 각각 저장한다 (02 D38).
             */
            OffsetDateTime occurredAt,
            @Valid Recommendation recommendation
    ) {
    }

    /**
     * 추천에서 비롯된 이벤트에만 싣는다. **2개뿐** — 지면·순위는 서버가 listId로 조회해 붙이므로
     * FE가 보내도 쓰이지 않는다 (노션 E-1 ③.5).
     */
    public record Recommendation(@Size(max = 36) String recommendationRequestId,
                                 @Size(max = 64) String listId) {
    }
}
