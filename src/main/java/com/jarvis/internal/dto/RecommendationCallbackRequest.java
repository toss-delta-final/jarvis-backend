package com.jarvis.internal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * I-21 요청 (노션 API 명세 I-21 — 2026-07-30 다중 목록 확정). productIds 순서 유지가 계약.
 * reasons는 추천 카드용 이유(선택) — SSE의 채팅용 이유와 별개로 CH-5 카드에 echo된다.
 *
 * <p><b>2026-08-07 — 과도기 수용 제거.</b> 구 평평 3필드(최상위 {@code listId}·{@code productIds}·
 * {@code reasons})는 FastAPI 배포 시점을 묶지 않으려고 함께 받아주던 것인데, LLM 팀이
 * {@code lists[]}로 전환을 마쳐(jarvis-ai v0.17.1) 역할이 끝났다. 이제 {@code lists}만 받는다.
 *
 * <p>{@code listType}·{@code recommendationRequestId}도 <b>누락이 400</b>이다 — 종전엔 기본값으로
 * 채웠으나 그 값이 조용히 틀린 데이터를 만든다(노션 I-21 실패 응답 표, 2026-08-07 개정).
 * 형식·개수 상한은 서비스에서 검사한다(listId 문자 제약 · 목록당 9개 · 목록 10개).
 */
public record RecommendationCallbackRequest(@NotBlank String sessionId,
                                            @NotBlank String recommendationRequestId,
                                            @NotBlank String listType,
                                            Integer totalBudget,
                                            // @NotEmpty를 붙이지 않는다 — 노션 I-21은 "lists가 비었거나 10개 초과"를
                                            // fields 없는 VALIDATION_ERROR로 규정한다. 빈 배열과 누락을 같게 다루려면
                                            // 개수 상한과 같은 자리(서비스)에서 던져야 한다
                                            @Valid List<ListEntry> lists) {

    public record ListEntry(@NotBlank String listId,
                            String label,
                            @NotEmpty List<Long> productIds,
                            List<Reason> reasons) {
    }

    public record Reason(Long productId, String reason) {
    }
}
