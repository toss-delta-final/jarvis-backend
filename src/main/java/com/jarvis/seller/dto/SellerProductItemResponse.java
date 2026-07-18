package com.jarvis.seller.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** I-9 챗봇용 자사 상품 목록 행 (05 §I-9) — 수정 초안(draft) 생성의 읽기 소스라 상세 필드 포함 */
public record SellerProductItemResponse(Long productId, String name, String summary, JsonNode attributes,
                                        String description, int price, int originalPrice, String status,
                                        int stockQuantity, long displayedSalesCount, String category,
                                        String imageUrl) {
}
