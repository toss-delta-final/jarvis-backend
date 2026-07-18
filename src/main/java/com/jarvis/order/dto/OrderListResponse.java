package com.jarvis.order.dto;

import com.jarvis.order.Order;
import com.jarvis.order.OrderItem;
import com.jarvis.order.OrderItemStatus;
import com.jarvis.order.RepresentativeStatus;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;

/**
 * O-3 (04 §4) — representativeStatus는 대표 상태 enum 코드 8종(01 §4), 표시 문구는 FE 매핑.
 * imageUrl은 스냅샷이 아니라 현재 상품 이미지(표시용) — 상품 삭제 정책이 RESTRICT라 항상 존재.
 */
public record OrderListResponse(List<Summary> content, int page, int size,
                                long totalElements, int totalPages) {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    public record Summary(Long orderId, String orderNo, String representativeStatus, int totalAmount,
                          OffsetDateTime orderedAt, List<ItemSummary> items) {
    }

    public record ItemSummary(Long orderItemId, Long productId, String productName, String optionName,
                              int price, int quantity, String status, String imageUrl) {

        public static ItemSummary from(OrderItem item, String imageUrl) {
            return new ItemSummary(item.getId(), item.getProductId(), item.getProductName(),
                    item.getOptionName(), item.getPrice(), item.getQuantity(),
                    item.getStatus().name(), imageUrl);
        }
    }

    public static OrderListResponse from(Page<Order> orders,
                                         Map<Long, List<OrderItem>> itemsByOrder,
                                         Map<Long, String> imageUrlByProduct) {
        List<Summary> summaries = orders.getContent().stream().map(order -> {
            List<OrderItem> orderItems = itemsByOrder.getOrDefault(order.getId(), List.of());
            List<OrderItemStatus> statuses = orderItems.stream().map(OrderItem::getStatus).toList();
            return new Summary(order.getId(), order.orderNo(),
                    RepresentativeStatus.of(order.getStatus(), statuses).name(),
                    order.getTotalAmount(),
                    order.getCreatedAt().atZone(ZONE).toOffsetDateTime(),
                    orderItems.stream()
                            .map(item -> ItemSummary.from(item, imageUrlByProduct.get(item.getProductId())))
                            .toList());
        }).toList();
        return new OrderListResponse(summaries, orders.getNumber(), orders.getSize(),
                orders.getTotalElements(), orders.getTotalPages());
    }
}
