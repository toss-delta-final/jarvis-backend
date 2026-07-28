package com.jarvis.chat.dto;

import java.util.List;

/**
 * CH-5 응답 (04 §6 — 2026-07-18 확정) — 순서는 I-21 콜백 저장 순서.
 * 카드에 추천 이유(reason)를 함께 실어 FE가 조인 없이 렌더한다.
 * itemsDropped는 추천 시점 이후 품절·숨김으로 빠진 개수 — 0이어도 항상 내려간다(노션 CH-5).
 */
public record RecommendationListResponse(String listId, List<RecommendedCardResponse> items,
                                         int itemsDropped) {
}
