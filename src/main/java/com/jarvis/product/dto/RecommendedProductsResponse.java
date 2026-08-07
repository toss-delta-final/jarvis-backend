package com.jarvis.product.dto;

import com.jarvis.global.response.StringId;
import java.util.List;

/**
 * P-5 개인화 추천 응답 (노션 P-5) — 카드 필드는 P-4와 같고 <b>여기에 추천 상관키 3종과 카드별
 * {@code reason}이 붙는다</b>. "P-4와 동형"이라고만 적어두면 상관키가 통째로 빠지므로 주의.
 *
 * <p>{@code source}가 표시 분기의 축이다 — {@code POPULAR_FALLBACK}이면 FE는 "OO님을 위한 추천"
 * 제목과 {@code reason}을 띄우지 않는다.
 *
 * <p><b>fallback에도 상관키가 실린다.</b> 목록을 Spring이 만들었으므로 Spring이 발급한다 —
 * 없으면 FE가 인기상품 카드에 대해 쏘는 노출·클릭 이벤트가 <b>부모 없는 고아</b>가 되어
 * E-1의 서버 검증에서 버려진다.
 *
 * <p>와이어에 싣지 않는 것: {@code fallbackReason}·{@code cacheStatus}·{@code algorithmVersion}
 * — FE 동작이 달라지지 않는 값이다. {@code fallbackReason}은 {@code recommendation_generated}
 * 이벤트에만 저장해 장애 관측에 쓴다.
 */
public record RecommendedProductsResponse(String source, String recommendationRequestId,
                                          String listId, List<Item> items) {

    /** {@code reason}은 {@code POPULAR_FALLBACK}일 때 null이다 — 키는 유지한다(CH-5와 동일 규칙) */
    public record Item(@StringId Long productId, String name, String brandName,
                       int price, int originalPrice, String imageUrl,
                       double rating, long reviewCount, String reason) {

        public static Item of(ProductCardResponse card, String reason) {
            return new Item(card.productId(), card.name(), card.brandName(),
                    card.price(), card.originalPrice(), card.imageUrl(),
                    card.rating(), card.reviewCount(), reason);
        }
    }
}
