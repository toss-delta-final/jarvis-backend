package com.jarvis.brand.dto;

import com.jarvis.brand.Brand;
import com.jarvis.category.dto.CategoryTreeResponse;
import com.jarvis.product.dto.ProductCardPageResponse;
import java.util.List;

/** P-6 — 브랜드 소개 + 필터 축(소분류) + 상품 목록 (04 §2) */
public record BrandDetailResponse(Long id, String name, String logoUrl, String description,
                                  List<CategoryTreeResponse.Child> categories,
                                  ProductCardPageResponse products) {

    public static BrandDetailResponse from(Brand brand, List<CategoryTreeResponse.Child> categories,
                                           ProductCardPageResponse products) {
        return new BrandDetailResponse(brand.getId(), brand.getName(), brand.getLogoUrl(),
                brand.getDescription(), categories, products);
    }
}
