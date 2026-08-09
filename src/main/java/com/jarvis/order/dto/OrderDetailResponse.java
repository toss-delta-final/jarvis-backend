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
import java.util.function.Predicate;

/**
 * O-4 (04 §4) — 아이템별 가능 액션(canCancel/canReturn/canReview)은 01 §3 매트릭스를 서버가 계산.
 * FE는 boolean만 보고 버튼 노출(상태 판단 중복 구현 금지). canExchange는 D11 제거로 없음.
 * NON_NULL 금지 — paidAt 등 미확정 값은 키 생략이 아니라 명시적 null(노션 O-4, FE 코멘트 반영).
 */
public record OrderDetailResponse(@StringId Long orderId, String orderNo, String status,
                                  String representativeStatus,
                                  String paymentMethod, int totalAmount,
                                  OffsetDateTime orderedAt, OffsetDateTime paidAt,
                                  ShippingAddress address, String deliveryRequest,
                                  List<Item> items) {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    public record ShippingAddress(String recipient, String phone, String zipCode,
                                  String address1, String address2) {
    }

    /**
     * status는 주문 아이템의 배송 상태, purchaseState는 그 상품이 지금 살 수 있는 상태인지다 —
     * 이름은 비슷하지만 다른 축이다. FE는 purchaseState=HIDDEN이면 상세 링크를 걸지 않고
     * "판매 종료"로 표시한다(상품 자체는 스냅샷이라 행은 그대로 보인다).
     */
    public record Item(@StringId Long orderItemId, @StringId Long productId,
                       String productName, String optionName,
                       int price, int originalPrice, int quantity, String status, String imageUrl,
                       String purchaseState,
                       boolean canCancel, boolean canReturn, boolean canReview) {

        /** stockQuantity는 상품의 옵션 재고 합계 — 목록(OrderListResponse)과 같은 기준이다 (02 D33 개정) */
        public static Item from(OrderItem item, Product product, int stockQuantity,
                                boolean reviewWritten) {
            OrderItemStatus status = item.getStatus();
            return new Item(item.getId(), item.getProductId(), item.getProductName(),
                    item.getOptionName(), item.getPrice(), item.getOriginalPrice(),
                    item.getQuantity(), status.name(),
                    product == null ? null : product.getImageUrl(),
                    product == null ? null
                            : PurchaseState.of(product.getStatus(), stockQuantity).name(),
                    status.canCancel(), status.canReturn(),
                    status.canReview() && !reviewWritten);
        }
    }

    public static OrderDetailResponse from(Order order, List<OrderItem> orderItems,
                                           Map<Long, Product> productById,
                                           Map<Long, Integer> stockByProduct,
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
                        .map(item -> Item.from(item, productById.get(item.getProductId()),
                                stockByProduct.getOrDefault(item.getProductId(), 0),
                                reviewWritten.test(item.getId())))
                        .toList());
    }
}
