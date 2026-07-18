package com.jarvis.order.dto;

import com.jarvis.order.ClaimStatus;
import com.jarvis.order.ClaimType;
import java.time.LocalDateTime;

/** O-6 조회 행 프로젝션 — orderNo 파생용 주문 id·created_at 동반 (02 D24) */
public record ClaimRow(Long claimId, ClaimType type, ClaimStatus status, String reason,
                       LocalDateTime requestedAt, LocalDateTime processedAt,
                       Long orderItemId, String productName, String optionName,
                       int price, int quantity,
                       Long orderId, LocalDateTime orderCreatedAt) {
}
