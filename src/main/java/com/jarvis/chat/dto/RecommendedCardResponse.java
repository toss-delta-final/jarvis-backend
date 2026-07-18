package com.jarvis.chat.dto;

import com.jarvis.product.dto.ProductCardResponse;

/**
 * CH-5 카드 — 공통 카드 필드 + 추천 이유(2026-07-18 LLM 합의: SSE는 채팅용,
 * 카드용 reason은 I-21 콜백으로 받아 여기서 echo). reason 없으면 null.
 */
public record RecommendedCardResponse(Long productId, String name, String brandName,
                                      int price, int originalPrice, String imageUrl,
                                      double rating, long reviewCount, boolean purchasable,
                                      String reason) {

    public static RecommendedCardResponse of(ProductCardResponse card, String reason) {
        return new RecommendedCardResponse(card.productId(), card.name(), card.brandName(),
                card.price(), card.originalPrice(), card.imageUrl(),
                card.rating(), card.reviewCount(), card.purchasable(), reason);
    }
}
