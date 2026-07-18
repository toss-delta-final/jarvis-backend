package com.jarvis.product.dto;

import java.util.List;
import org.springframework.data.domain.Page;
import com.jarvis.product.Product;

public record ProductCardPageResponse(List<ProductCardResponse> items, int page, int size,
                                      long totalElements, int totalPages) {

    public static ProductCardPageResponse from(Page<Product> productPage, List<ProductCardResponse> items) {
        return new ProductCardPageResponse(items, productPage.getNumber(), productPage.getSize(),
                productPage.getTotalElements(), productPage.getTotalPages());
    }
}
