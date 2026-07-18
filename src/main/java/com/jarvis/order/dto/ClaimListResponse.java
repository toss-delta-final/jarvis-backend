package com.jarvis.order.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jarvis.order.Order;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * O-6 (04 §4) — 행에 orderNo 포함(07-17 FE). 환불 금액 = 스냅샷 가격 × 수량 —
 * 모의 결제라 실제 환불 처리는 없고 표시용 (01 §5).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClaimListResponse(List<Item> items, int page, int size,
                                long totalElements, int totalPages) {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    public record Item(Long claimId, String orderNo, Long orderItemId,
                       String productName, String optionName, int quantity, int refundAmount,
                       String type, String status, String reason,
                       OffsetDateTime requestedAt, OffsetDateTime processedAt) {

        public static Item from(ClaimRow row) {
            return new Item(row.claimId(),
                    Order.orderNo(row.orderId(), row.orderCreatedAt()),
                    row.orderItemId(), row.productName(), row.optionName(), row.quantity(),
                    row.price() * row.quantity(),
                    row.type().name(), row.status().name(), row.reason(),
                    row.requestedAt().atZone(ZONE).toOffsetDateTime(),
                    row.processedAt() == null ? null : row.processedAt().atZone(ZONE).toOffsetDateTime());
        }
    }

    public static ClaimListResponse from(Page<ClaimRow> rows) {
        return new ClaimListResponse(rows.getContent().stream().map(Item::from).toList(),
                rows.getNumber(), rows.getSize(), rows.getTotalElements(), rows.getTotalPages());
    }
}
