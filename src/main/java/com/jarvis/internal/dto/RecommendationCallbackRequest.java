package com.jarvis.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** I-21 요청 (05 §I-21 — 제안, 스키마 OPEN) — productIds 순서 유지가 계약 */
public record RecommendationCallbackRequest(@NotBlank String sessionId,
                                            @NotBlank String listId,
                                            @NotEmpty List<Long> productIds) {
}
