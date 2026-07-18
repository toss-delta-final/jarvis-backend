package com.jarvis.internal;

import com.jarvis.chat.RecommendationListService;
import com.jarvis.global.response.ApiResponse;
import com.jarvis.internal.dto.RecommendationCallbackRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * I-21 (04 §10 — 2026-07-18 LLM 합의로 확정) — FastAPI가 리랭킹 확정 Top5(+카드용 reason)를
 * 저장한 뒤에만 SSE products.ready{listId}를 발행한다(콜백 실패 시 발행 금지 — 05 §1-2-1). CH-5와 쌍.
 */
@RestController
@RequiredArgsConstructor
public class InternalRecommendationController {

    private final RecommendationListService recommendationListService;

    @PostMapping("/internal/recommendations")
    public ApiResponse<Void> store(@Valid @RequestBody RecommendationCallbackRequest request) {
        recommendationListService.store(request.sessionId(), request.listId(),
                request.productIds(), request.reasons());
        return ApiResponse.success(null);
    }
}
