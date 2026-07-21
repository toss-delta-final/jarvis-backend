package com.jarvis.order.dto;

import com.jarvis.order.Order;
import com.jarvis.order.OrderItem;
import com.jarvis.order.OrderItemStatus;
import com.jarvis.order.RepresentativeStatus;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * O-4 (04 §4) — 아이템별 가능 액션(canCancel/canReturn/canReview)은 01 §3 매트릭스를 서버가 계산.
 * FE는 boolean만 보고 버튼 노출(상태 판단 중복 구현 금지). canExchange는 D11 제거로 없음.
 * NON_NULL 금지 — paidAt 등 미확정 값은 키 생략이 아니라 명시적 null(노션 O-4, FE 코멘트 반영).
 */
public record OrderDetailResponse(Long orderId, String orderNo, String status, String representativeStatus,
                                  String paymentMethod, int totalAmount,
                                  OffsetDateTime orderedAt, OffsetDateTime paidAt,
                                  ShippingAddress address, String deliveryRequest,
                                  List<Item> items) {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    public record ShippingAddress(String recipient, String phone, String zipCode,
                                  String address1, String address2) {
    }

    public record Item(Long orderItemId, Long productId, String productName, String optionName,
                       int price, int originalPrice, int quantity, String status, String imageUrl,
                       boolean canCancel, boolean canReturn, boolean canReview) {

        public static Item from(OrderItem item, String imageUrl, boolean reviewWritten) {
            OrderItemStatus status = item.getStatus();
            return new Item(item.getId(), item.getProductId(), item.getProductName(),
                    item.getOptionName(), item.getPrice(), item.getOriginalPrice(),
                    item.getQuantity(), status.name(), imageUrl,
                    status.canCancel(), status.canReturn(),
                    status.canReview() && !reviewWritten);
        }
    }

    public static OrderDetailResponse from(Order order, List<OrderItem> orderItems,
                                           Map<Long, String> imageUrlByProduct,
                                           Predicate<Long> reviewWritten) {
        List<OrderItemStatus> statuses = orderItems.stream().map(OrderItem::getStatus).toList();
        return new OrderDetailResponse(order.getId(), order.orderNo(), order.getStatus().name(),
                RepresentativeStatus.of(order.getStatus(), statuses).name(),
                order.getPaymentMethod(), order.getTotalAmount(),
                order.getCreatedAt().atZone(ZONE).toOffsetDateTime(),
                order.getPaidAt() == null ? null : order.getPaidAt().atZone(ZONE).toOffsetDateTime(),
                new ShippingAddress(order.getRecipient(), order.getPhone(), order.getZipCode(),
                        order.getAddress1(), order.getAddress2()),
                order.getDeliveryRequest(),
                orderItems.stream()
                        .map(item -> Item.from(item, imageUrlByProduct.get(item.getProductId()),
                                reviewWritten.test(item.getId())))
                        .toList());
    }
}
