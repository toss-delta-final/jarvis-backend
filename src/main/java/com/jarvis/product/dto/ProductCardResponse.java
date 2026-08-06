package com.jarvis.product.dto;

import com.jarvis.global.response.StringId;
import com.jarvis.product.Product;
import com.jarvis.product.PurchaseState;
import com.jarvis.review.dto.RatingStats;

/** 상품 카드 공통 모양 — P-6·M-4·M-7 (04 §2). P-4·CH-5는 못 사는 상품을 드롭해 purchaseState를 뺀 모양 */
public record ProductCardResponse(@StringId Long productId, String name, String brandName,
                                  int price, int originalPrice, String imageUrl,
                                  double rating, long reviewCount, String purchaseState) {

    public static ProductCardResponse from(Product product, String brandName, RatingStats stats) {
        return new ProductCardResponse(product.getId(), product.getName(), brandName,
                product.getPrice(), product.getOriginalPrice(), product.getImageUrl(),
                stats.average(), stats.count(), PurchaseState.of(product).name());
    }

    public boolean available() {
        return PurchaseState.AVAILABLE.name().equals(purchaseState);
    }
}
