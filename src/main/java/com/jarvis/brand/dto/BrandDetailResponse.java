package com.jarvis.brand.dto;

import com.jarvis.brand.Brand;
import com.jarvis.category.dto.CategoryTreeResponse;
import com.jarvis.product.dto.ProductCardPageResponse;
import java.util.List;

/** P-6 — 브랜드 소개 + 필터 축(소분류) + 상품 목록 (04 §2). 노션 명세대로 brand 객체로 중첩 */
public record BrandDetailResponse(BrandSummary brand, ProductCardPageResponse products) {

    public record BrandSummary(Long id, String name, String logoUrl, String description,
                               List<CategoryTreeResponse.Child> categories) {
    }

    public static BrandDetailResponse from(Brand brand, List<CategoryTreeResponse.Child> categories,
                                           ProductCardPageResponse products) {
        return new BrandDetailResponse(new BrandSummary(brand.getId(), brand.getName(),
                brand.getLogoUrl(), brand.getDescription(), categories), products);
    }
}
