package com.jarvis.seller.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * I-29 자사 주문 조회 (노션 I-29 — 현재 상태 스냅샷). S-2의 internal 판으로, 주문 단위 파생 규칙
 * (대표상태·claimStatus·orderNo·자사 금액만 집계)은 그대로 상속하고 자사 items 배열을 더한다.
 * 타사 아이템의 이름·금액은 싣지 않는다(S-2 정보 누출 방지 규칙).
 *
 * <p>S-2와 달리 myItemCount·representativeProduct는 없다 — 상속하는 건 파생 규칙이지 응답 필드가
 * 아니며, 개수는 items 길이로 세고 대표상품은 화면용 장치라 챗봇엔 쓸모가 없다(노션 I-29 확정).
 * id는 숫자 그대로 — 문자열화는 FE가 보는 공개 응답에만 적용된다(04 2026-08-06).
 */
public record SellerOrderInternalResponse(Map<String, Long> tabCounts, List<Row> rows, long total) {

    public record Row(Long orderId, String orderNo, OffsetDateTime orderedAt, String recipientName,
                      String paymentMethod, long myItemsAmount, String status, String claimStatus,
                      List<Item> items) {
    }

    /**
     * activeClaimStatus는 claim 테이블이 아니라 아이템 status에서 직접 파생한다(01 §5 동기 전이).
     * 이미 끝난 클레임(CANCELLED/RETURNED)은 status 자체에 드러나므로 여기선 null — "active"의 뜻 그대로.
     */
    public record Item(Long orderItemId, Long productId, String name, String optionName,
                       int quantity, int price, String status, String activeClaimStatus) {
    }
}
