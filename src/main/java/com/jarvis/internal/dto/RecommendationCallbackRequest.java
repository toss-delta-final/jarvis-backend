package com.jarvis.internal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * I-21 요청 (노션 API 명세 I-21 — 2026-07-30 다중 목록 확정). productIds 순서 유지가 계약.
 * reasons는 추천 카드용 이유(선택) — SSE의 채팅용 이유와 별개로 CH-5 카드에 echo된다.
 * <p>
 * 정본은 {@code lists[]}이고, 평평한 3개 필드({@code listId}·{@code productIds}·{@code reasons})는
 * FastAPI가 전환하기 전까지만 함께 받는다 — 양쪽 배포 시점을 묶지 않기 위한 과도기 수용이므로
 * 전환이 끝나면 제거한다. 형식·개수 상한은 서비스에서 검사한다(listId 문자 제약 · 목록당 9개 · 목록 10개).
 */
public record RecommendationCallbackRequest(@NotBlank String sessionId,
                                            String recommendationRequestId,
                                            String listType,
                                            Integer totalBudget,
                                            @Valid List<ListEntry> lists,
                                            String listId,
                                            List<Long> productIds,
                                            List<Reason> reasons) {

    public record ListEntry(@NotBlank String listId,
                            String label,
                            @NotEmpty List<Long> productIds,
                            List<Reason> reasons) {
    }

    public record Reason(Long productId, String reason) {
    }

    /** 과도기 평평 구조를 목록 1건으로 접어, 서비스는 한 가지 모양만 다루게 한다. */
    public List<ListEntry> resolvedLists() {
        if (lists != null) {
            return lists;
        }
        return List.of(new ListEntry(listId, null, productIds, reasons));
    }
}
