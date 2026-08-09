package com.jarvis.seller.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * I-9 챗봇용 자사 상품 목록 행 (05 §I-9) — 수정 초안(draft) 생성의 읽기 소스라 상세 필드 포함.
 * {@code stockQuantity}는 옵션 재고의 <b>합계 파생값</b>이고, 옵션별 값과 I-11에 넣을 id는
 * {@code stocks}에 있다 (02 D33 개정, 2026-08-09).
 */
public record SellerProductItemResponse(Long productId, String name, String summary, JsonNode attributes,
                                        String description, int price, int originalPrice, String status,
                                        int stockQuantity, List<StockView> stocks,
                                        long displayedSalesCount, String category,
                                        String imageUrl) {
}
