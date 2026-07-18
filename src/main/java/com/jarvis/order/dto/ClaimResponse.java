package com.jarvis.order.dto;

import com.jarvis.order.Claim;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** O-5 응답 — 신청 접수 결과 */
public record ClaimResponse(Long claimId, Long orderItemId, String type, String status,
                            OffsetDateTime requestedAt) {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    public static ClaimResponse from(Claim claim) {
        return new ClaimResponse(claim.getId(), claim.getOrderItemId(),
                claim.getType().name(), claim.getStatus().name(),
                claim.getCreatedAt().atZone(ZONE).toOffsetDateTime());
    }
}
