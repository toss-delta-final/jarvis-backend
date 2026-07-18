package com.jarvis.order.dto;

import com.jarvis.order.Order;
import com.jarvis.order.OrderItem;
import com.jarvis.order.OrderItemStatus;
import com.jarvis.order.RepresentativeStatus;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * I-4 주문 상태 요약 (05 §I-4) — 문의 챗봇 전용. statusText는 한국어 표시 문자열로
 * LLM이 그대로 인용한다(FE용 API는 enum 코드만 — 07-17 FE와 다른 소비자라 여기만 한국어).
 */
public record InternalOrderStatusResponse(List<Summary> orders) {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private static final Map<OrderItemStatus, String> STATUS_TEXT = Map.of(
            OrderItemStatus.PENDING, "결제 대기",
            OrderItemStatus.ORDERED, "주문 완료",
            OrderItemStatus.SHIPPING, "배송중",
            OrderItemStatus.DELIVERED, "배송 완료",
            OrderItemStatus.CONFIRMED, "구매 확정",
            OrderItemStatus.CANCEL_REQUESTED, "취소 접수",
            OrderItemStatus.CANCELLED, "취소 완료",
            OrderItemStatus.RETURN_REQUESTED, "반품 접수",
            OrderItemStatus.RETURNED, "반품 완료");

    public record Summary(Long orderId, OffsetDateTime orderedAt, String representativeStatus,
                          List<Item> items) {
    }

    public record Item(String productName, String status, String statusText) {

        public static Item from(OrderItem item) {
            return new Item(item.getProductName(), item.getStatus().name(),
                    STATUS_TEXT.get(item.getStatus()));
        }
    }

    public static InternalOrderStatusResponse from(List<Order> orders,
                                                   Map<Long, List<OrderItem>> itemsByOrder) {
        List<Summary> summaries = orders.stream().map(order -> {
            List<OrderItem> items = itemsByOrder.getOrDefault(order.getId(), List.of());
            return new Summary(order.getId(),
                    order.getCreatedAt().atZone(ZONE).toOffsetDateTime(),
                    RepresentativeStatus.of(order.getStatus(),
                            items.stream().map(OrderItem::getStatus).toList()).name(),
                    items.stream().map(Item::from).toList());
        }).toList();
        return new InternalOrderStatusResponse(summaries);
    }
}
