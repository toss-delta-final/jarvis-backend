package com.jarvis.chat.dto;

import java.util.List;

/**
 * CH-5 응답 (04 §6 — 2026-07-18 확정) — 순서는 I-21 콜백 저장 순서.
 * 카드에 추천 이유(reason)를 함께 실어 FE가 조인 없이 렌더한다.
 */
public record RecommendationListResponse(String listId, List<RecommendedCardResponse> items) {
}
