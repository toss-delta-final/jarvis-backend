package com.jarvis.product.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jarvis.brand.Brand;
import com.jarvis.category.Category;
import com.jarvis.product.Product;
import com.jarvis.product.ProductOption;
import com.jarvis.review.dto.RatingStats;
import java.util.List;

/** P-2 (04 §2) — 대표 이미지 단일(02 D14), 평점은 실시간 집계(02 D9) */
public record ProductDetailResponse(Long id, String name, int price, int originalPrice,
                                    int stockQuantity, boolean purchasable, String status,
                                    String imageUrl, String summary, JsonNode attributes,
                                    String description, CategorySummary category,
                                    BrandSummary brand, List<OptionResponse> options,
                                    Rating rating) {

    public record CategorySummary(Long id, String name) {
        public static CategorySummary from(Category category) {
            return new CategorySummary(category.getId(), category.getName());
        }
    }

    public record BrandSummary(Long id, String name, String logoUrl) {
        public static BrandSummary from(Brand brand) {
            return new BrandSummary(brand.getId(), brand.getName(), brand.getLogoUrl());
        }
    }

    public record OptionResponse(Long optionId, String name, int extraPrice) {
        public static OptionResponse from(ProductOption option) {
            return new OptionResponse(option.getId(), option.getName(), option.getExtraPrice());
        }
    }

    public record Rating(double average, long count) {
        public static Rating from(RatingStats stats) {
            return new Rating(stats.average(), stats.count());
        }
    }

    public static ProductDetailResponse from(Product product, JsonNode attributes,
                                             Category category, Brand brand,
                                             List<ProductOption> options, RatingStats stats) {
        return new ProductDetailResponse(product.getId(), product.getName(), product.getPrice(),
                product.getOriginalPrice(), product.getStockQuantity(), product.isPurchasable(),
                product.getStatus().name(), product.getImageUrl(), product.getSummary(),
                attributes, product.getDescription(), CategorySummary.from(category),
                BrandSummary.from(brand), options.stream().map(OptionResponse::from).toList(),
                Rating.from(stats));
    }
}
