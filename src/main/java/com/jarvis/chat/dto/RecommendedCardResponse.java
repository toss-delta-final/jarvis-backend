package com.jarvis.chat.dto;

import com.jarvis.global.response.StringId;
import com.jarvis.product.dto.ProductCardResponse;

/**
 * CH-5 카드 — 공통 카드 필드 + 추천 이유(2026-07-18 LLM 합의: SSE는 채팅용,
 * 카드용 reason은 I-21 콜백으로 받아 여기서 echo). reason 없으면 null.
 *
 * <p>못 사는 상품은 목록에서 드롭하므로 구매 가능 여부 필드를 두지 않는다 — 항상 살 수 있는 것만
 * 남아 의미가 없다. P-4·P-5가 같은 이유로 이 필드를 뺀 것과 동일하다
 * (2026-07-28 P-4 결정, 2026-08-05 CH-5에도 적용).
 */
public record RecommendedCardResponse(@StringId Long productId, String name, String brandName,
                                      int price, int originalPrice, String imageUrl,
                                      double rating, long reviewCount, String reason) {

    public static RecommendedCardResponse of(ProductCardResponse card, String reason) {
        return new RecommendedCardResponse(card.productId(), card.name(), card.brandName(),
                card.price(), card.originalPrice(), card.imageUrl(),
                card.rating(), card.reviewCount(), reason);
    }
}
