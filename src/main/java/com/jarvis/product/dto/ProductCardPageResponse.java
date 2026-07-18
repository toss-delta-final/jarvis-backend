package com.jarvis.product.dto;

import java.util.List;
import org.springframework.data.domain.Page;
import com.jarvis.product.Product;

/** P-6 페이지 모양 — 목록 키는 content (노션 명세 정합화, 2026-07-18) */
public record ProductCardPageResponse(List<ProductCardResponse> content, int page, int size,
                                      long totalElements, int totalPages) {

    public static ProductCardPageResponse from(Page<Product> productPage, List<ProductCardResponse> content) {
        return new ProductCardPageResponse(content, productPage.getNumber(), productPage.getSize(),
                productPage.getTotalElements(), productPage.getTotalPages());
    }
}
