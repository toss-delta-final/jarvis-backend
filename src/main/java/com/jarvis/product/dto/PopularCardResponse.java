package com.jarvis.product.dto;

import com.jarvis.global.response.StringId;

/**
 * P-4 인기 상품 카드 — 공통 카드에서 `purchaseState`만 뺀 모양(노션 P-4 2026-07-28 결정).
 * 품절을 목록에서 아예 제외하므로 값이 항상 true라 의미가 없다.
 *
 * <p><b>P-5는 이 모양이 아니다</b>(2026-08-07 정정) — 카드 필드는 같지만 추천 상관키 3종과
 * 카드별 {@code reason}이 더 붙는다. {@link RecommendedProductsResponse} 참조.
 */
public record PopularCardResponse(@StringId Long productId, String name, String brandName,
                                  int price, int originalPrice, String imageUrl,
                                  double rating, long reviewCount) {

    public static PopularCardResponse of(ProductCardResponse card) {
        return new PopularCardResponse(card.productId(), card.name(), card.brandName(),
                card.price(), card.originalPrice(), card.imageUrl(),
                card.rating(), card.reviewCount());
    }
}
