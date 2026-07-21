package com.jarvis.seller.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * S-2 자사 주문 목록 (노션 S-2, 2026-07-21 [BREAKING] 아이템 단위 → 주문 단위) — 금액·건수·상태는
 * 자사(brandId) 아이템만 집계. 타사 상품의 이름·금액은 응답에 절대 싣지 않는다(정보 누출 방지).
 * tabCounts 키는 ALL/ORDERED/SHIPPING/DELIVERED/CLAIM, 필터와 무관하게 항상 전량 기준.
 */
public record SellerOrderListResponse(Map<String, Long> tabCounts, List<Row> content,
                                      int page, int size, long totalElements, int totalPages) {

    /**
     * status는 대표 상태(자사 아이템의 가장 뒤진 단계). claimStatus는 활성 클레임이 있으면
     * CANCEL_REQUESTED/RETURN_REQUESTED, 없으면 null — FE가 있으면 배지를 취소요청/반품요청으로 덮어쓴다.
     */
    public record Row(Long orderId, String orderNo, OffsetDateTime orderedAt, String recipientName,
                      String paymentMethod, long myItemsAmount, int myItemCount,
                      RepresentativeProduct representativeProduct, String status, String claimStatus) {
    }

    public record RepresentativeProduct(Long productId, String name, String imageUrl, String optionName) {
    }
}
