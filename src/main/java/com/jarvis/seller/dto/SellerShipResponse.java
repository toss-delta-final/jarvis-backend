package com.jarvis.seller.dto;

import java.time.OffsetDateTime;

/**
 * I-30 발송 처리 응답 (노션 I-30) — id는 숫자 그대로(문자열화는 공개 응답 한정, 04 2026-08-06).
 * fromStatus를 함께 내리는 건 에이전트가 "무엇을 어디서 어디로 보냈는지" 그대로 말할 수 있게 하려는 것이다.
 */
public record SellerShipResponse(Long orderItemId, String fromStatus, String toStatus,
                                 OffsetDateTime changedAt) {
}
