package com.jarvis.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * I-21 요청 (05 §I-21 — 2026-07-18 LLM 합의로 확정) — productIds 순서 유지가 계약.
 * reasons는 추천 카드용 이유(선택) — SSE의 채팅용 이유와 별개로 CH-5 카드에 echo된다.
 */
public record RecommendationCallbackRequest(@NotBlank String sessionId,
                                            @NotBlank String listId,
                                            @NotEmpty List<Long> productIds,
                                            List<Reason> reasons) {

    public record Reason(Long productId, String reason) {
    }
}
