package com.jarvis.order.dto;

import com.jarvis.global.response.StringId;
import com.jarvis.order.Order;
import com.jarvis.order.OrderItem;
import com.jarvis.order.OrderItemStatus;
import com.jarvis.order.RepresentativeStatus;
import com.jarvis.product.Product;
import com.jarvis.product.PurchaseState;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;

/**
 * O-3 (04 §4) — representativeStatus는 대표 상태 enum 코드 8종(01 §4), 표시 문구는 FE 매핑.
 * imageUrl·purchaseState는 스냅샷이 아니라 현재 상품에서 읽는다(표시용) — 상품 삭제 정책이
 * RESTRICT이고 삭제도 soft라 항상 존재. purchaseState=HIDDEN이면 FE가 상세 링크를 걸지 않는다.
 */
public record OrderListResponse(List<Summary> content, int page, int size,
                                long totalElements, int totalPages) {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    public record Summary(@StringId Long orderId, String orderNo, String representativeStatus,
                          int totalAmount,
                          OffsetDateTime orderedAt, List<ItemSummary> items) {
    }

    public record ItemSummary(@StringId Long orderItemId, @StringId Long productId,
                              String productName, String optionName,
                              int price, int quantity, String status, String imageUrl,
                              String purchaseState) {

        /**
         * @param stockQuantity 상품의 옵션 재고 <b>합계</b> (02 D33 개정). 주문 내역의
         *                      purchaseState는 "이 상품을 다시 살 수 있나"라서 옵션이 아니라 상품
         *                      기준으로 둔다 — 담았던 옵션은 그새 없어졌을 수도 있다
         */
        public static ItemSummary from(OrderItem item, Product product, int stockQuantity) {
            return new ItemSummary(item.getId(), item.getProductId(), item.getProductName(),
                    item.getOptionName(), item.getPrice(), item.getQuantity(),
                    item.getStatus().name(),
                    product == null ? null : product.getImageUrl(),
                    product == null ? null
                            : PurchaseState.of(product.getStatus(), stockQuantity).name());
        }
    }

    public static OrderListResponse from(Page<Order> orders,
                                         Map<Long, List<OrderItem>> itemsByOrder,
                                         Map<Long, Product> productById,
                                         Map<Long, Integer> stockByProduct) {
        List<Summary> summaries = orders.getContent().stream().map(order -> {
            List<OrderItem> orderItems = itemsByOrder.getOrDefault(order.getId(), List.of());
            List<OrderItemStatus> statuses = orderItems.stream().map(OrderItem::getStatus).toList();
            return new Summary(order.getId(), order.orderNo(),
                    RepresentativeStatus.of(order.getStatus(), statuses).name(),
                    order.getTotalAmount(),
                    order.getCreatedAt().atZone(ZONE).toOffsetDateTime(),
                    orderItems.stream()
                            .map(item -> ItemSummary.from(item, productById.get(item.getProductId()),
                                    stockByProduct.getOrDefault(item.getProductId(), 0)))
                            .toList());
        }).toList();
        return new OrderListResponse(summaries, orders.getNumber(), orders.getSize(),
                orders.getTotalElements(), orders.getTotalPages());
    }
}
