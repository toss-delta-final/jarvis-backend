package com.jarvis.seller.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * S-3 판매자 화면용 자사 상품 목록 (04 §7, 노션 S-3) — HIDDEN도 노출(본인 화면),
 * 상세 필드(description·attributes)는 I-9(챗봇 조회)/I-11(챗봇 수정) 소관.
 * displayStatus·tabCounts·createdAt은 S-3 전용(화면용) — I-9에는 넣지 않는다(노션 S-3 구현 메모).
 * tabCounts 키는 ALL/ON_SALE/SOLD_OUT/HIDDEN, 필터와 무관하게 항상 전량 기준.
 */
public record SellerProductListResponse(Map<String, Long> tabCounts, List<Row> content,
                                        int page, int size, long totalElements, int totalPages) {

    public record Row(Long productId, String name, String imageUrl, String category,
                      int price, int originalPrice, int stockQuantity, long displayedSalesCount,
                      String status, String displayStatus, OffsetDateTime createdAt,
                      OffsetDateTime updatedAt) {
    }
}
