package com.jarvis.seller.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * S-3 판매자 화면용 자사 상품 목록 (04 §7, 노션 S-3) — HIDDEN도 노출(본인 화면),
 * 상세 필드(description·attributes)는 I-9(챗봇 조회)/I-11(챗봇 수정) 소관.
 */
public record SellerProductListResponse(List<Row> content, int page, int size,
                                        long totalElements, int totalPages) {

    public record Row(Long productId, String name, int price, int originalPrice, int stockQuantity,
                      String status, long displayedSalesCount, String category, String imageUrl,
                      OffsetDateTime updatedAt) {
    }
}
