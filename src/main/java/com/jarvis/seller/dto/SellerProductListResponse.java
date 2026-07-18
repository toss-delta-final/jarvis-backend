package com.jarvis.seller.dto;

import java.util.List;

/**
 * S-3 판매자 화면용 자사 상품 목록 (04 §7) — HIDDEN도 노출(본인 화면),
 * 상세 필드(description·attributes)는 I-9/S-5 수정 화면 소관.
 */
public record SellerProductListResponse(List<Row> items, int page, int size,
                                        long totalElements, int totalPages) {

    public record Row(Long productId, String name, int price, int originalPrice, int stockQuantity,
                      String status, long displayedSalesCount, String category, String imageUrl) {
    }
}
