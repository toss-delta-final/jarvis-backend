package com.jarvis.order.dto;

import com.jarvis.order.Order;
import com.jarvis.order.OrderItem;
import com.jarvis.order.RepresentativeStatus;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** I-19 구매 이력 목록 (05 §I-19) — CS 챗봇용. shippingFee는 항상 0(배송비 없음 확정) */
public record InternalOrderListResponse(List<Summary> orders) {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    public record Summary(Long orderId, String orderNo, String representativeStatus,
                          int itemsTotal, int shippingFee, int totalAmount, OffsetDateTime orderedAt,
                          List<Item> items) {
    }

    /** categoryName — 상품 소분류명(노션 I-19 응답 필드, 현재 상품 기준 표시용) */
    public record Item(Long orderItemId, Long productId, String productName, String optionName,
                       int price, int quantity, String status, String categoryName) {

        public static Item from(OrderItem item, String categoryName) {
            return new Item(item.getId(), item.getProductId(), item.getProductName(),
                    item.getOptionName(), item.getPrice(), item.getQuantity(),
                    item.getStatus().name(), categoryName);
        }
    }

    public static InternalOrderListResponse from(List<Order> orders,
                                                 Map<Long, List<OrderItem>> itemsByOrder,
                                                 Map<Long, String> categoryNameByProduct) {
        List<Summary> summaries = orders.stream().map(order -> {
            List<OrderItem> items = itemsByOrder.getOrDefault(order.getId(), List.of());
            int itemsTotal = items.stream().mapToInt(item -> item.getPrice() * item.getQuantity()).sum();
            return new Summary(order.getId(), order.orderNo(),
                    RepresentativeStatus.of(order.getStatus(),
                            items.stream().map(OrderItem::getStatus).toList()).name(),
                    itemsTotal, 0, order.getTotalAmount(),
                    order.getCreatedAt().atZone(ZONE).toOffsetDateTime(),
                    items.stream()
                            .map(item -> Item.from(item, categoryNameByProduct.get(item.getProductId())))
                            .toList());
        }).toList();
        return new InternalOrderListResponse(summaries);
    }
}
