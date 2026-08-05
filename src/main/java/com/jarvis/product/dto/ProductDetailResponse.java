package com.jarvis.product.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jarvis.brand.Brand;
import com.jarvis.category.Category;
import com.jarvis.product.Product;
import com.jarvis.product.ProductOption;
import com.jarvis.product.PurchaseState;
import com.jarvis.review.dto.RatingStats;
import java.util.List;

/**
 * P-2 (04 §2) — 평점은 실시간 집계(02 D9).
 *
 * <p>이미지가 둘로 나뉜다: {@code imageUrl}은 상단 대표 1장(02 D14), {@code detailImages}는 하단에
 * 순서대로 나열할 상세 이미지 URL 배열(02 D42). <b>대표는 배열에 포함하지 않는다</b> — 화면에서 역할이
 * 달라서다. 배열 순서가 곧 노출 순서라 {@code {url, order}} 객체로 감싸지 않으며, 상세 이미지가 없는
 * 상품은 {@code null}이 아니라 빈 배열이다.
 */
public record ProductDetailResponse(Long id, String name, int price, int originalPrice,
                                    int stockQuantity, String purchaseState, String status,
                                    String imageUrl, List<String> detailImages,
                                    String summary, JsonNode attributes,
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
                                             List<ProductOption> options, RatingStats stats,
                                             List<String> detailImages) {
        return new ProductDetailResponse(product.getId(), product.getName(), product.getPrice(),
                product.getOriginalPrice(), product.getStockQuantity(), PurchaseState.of(product).name(),
                product.getStatus().name(), product.getImageUrl(), detailImages, product.getSummary(),
                attributes, product.getDescription(), CategorySummary.from(category),
                BrandSummary.from(brand), options.stream().map(OptionResponse::from).toList(),
                Rating.from(stats));
    }
}
