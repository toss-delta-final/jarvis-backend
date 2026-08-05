package com.jarvis.product.dto;

import java.util.List;

/** P-4 목록 래퍼 — data.items (카드에 purchaseState가 없다는 점만 ProductCardListResponse와 다르다) */
public record PopularCardListResponse(List<PopularCardResponse> items) {
}
