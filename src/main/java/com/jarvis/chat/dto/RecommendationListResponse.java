package com.jarvis.chat.dto;

import com.jarvis.product.dto.ProductCardResponse;
import java.util.List;

/**
 * CH-5 응답 (04 §6 — 스키마 OPEN, 임시) — 순서는 I-21 콜백 저장 순서.
 * reason은 SSE 소유라 여기 없음 — FE가 productId로 조인 (05 §1-2).
 */
public record RecommendationListResponse(String listId, List<ProductCardResponse> items) {
}
