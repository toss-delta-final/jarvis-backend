package com.jarvis.internal.dto;

import com.jarvis.product.dto.ProductCardResponse;
import java.util.List;

/**
 * I-28 응답 (노션 I-28) — M-4 전체 스키마를 그대로 재사용하되 <b>id만 숫자 BIGINT</b>다(§2.6).
 *
 * <p>AI가 실제로 쓰는 건 {@code productId}·{@code name}·{@code purchaseState}뿐이지만 응답을
 * 줄이지 않는다 — 별도 조회를 짜는 것보다 M-4 결과를 그대로 옮기는 편이 구현 비용이 낮다는 게
 * 노션 I-28의 판단이다. 여기서 하는 일도 필드 선별이 아니라 <b>id 직렬화 되돌리기</b> 하나다.
 */
public record InternalWishlistListResponse(List<Item> items) {

    public record Item(Long productId, String name, String brandName,
                       int price, int originalPrice, String imageUrl,
                       double rating, long reviewCount, String purchaseState) {

        public static Item from(ProductCardResponse card) {
            return new Item(card.productId(), card.name(), card.brandName(),
                    card.price(), card.originalPrice(), card.imageUrl(),
                    card.rating(), card.reviewCount(), card.purchaseState());
        }
    }

    public static InternalWishlistListResponse from(List<ProductCardResponse> cards) {
        return new InternalWishlistListResponse(cards.stream().map(Item::from).toList());
    }
}
