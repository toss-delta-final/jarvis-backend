package com.jarvis.product.dto;

import com.jarvis.product.Product;
import com.jarvis.review.dto.RatingStats;

/** 상품 카드 공통 모양 — P-4·P-6, P-7(Phase 5)과 동형 (04 §2) */
public record ProductCardResponse(Long productId, String name, String brandName,
                                  int price, int originalPrice, String imageUrl,
                                  double rating, long reviewCount, boolean purchasable) {

    public static ProductCardResponse from(Product product, String brandName, RatingStats stats) {
        return new ProductCardResponse(product.getId(), product.getName(), brandName,
                product.getPrice(), product.getOriginalPrice(), product.getImageUrl(),
                stats.average(), stats.count(), product.isPurchasable());
    }
}
