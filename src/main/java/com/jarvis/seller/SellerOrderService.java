package com.jarvis.seller;

import com.jarvis.order.Order;
import com.jarvis.order.OrderItem;
import com.jarvis.order.OrderItemRepository;
import com.jarvis.order.OrderRepository;
import com.jarvis.seller.dto.SellerOrderListResponse;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** S-2 (04 §7) — 자사 상품이 포함된 PAID 주문의 아이템 단위 목록(자사 아이템만) */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerOrderService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final OrderItemRepository orderItemRepository;
    private final OrderRepository orderRepository;

    public SellerOrderListResponse list(Long brandId, int page, int size) {
        Page<OrderItem> items = orderItemRepository.findSellerOrderItems(brandId,
                PageRequest.of(page, size));
        List<Long> orderIds = items.getContent().stream().map(OrderItem::getOrderId).distinct().toList();
        Map<Long, Order> orders = orderRepository.findAllById(orderIds).stream()
                .collect(Collectors.toMap(Order::getId, Function.identity()));
        List<SellerOrderListResponse.Row> rows = items.getContent().stream()
                .map(item -> toRow(item, orders.get(item.getOrderId())))
                .toList();
        return new SellerOrderListResponse(rows, items.getNumber(), items.getSize(),
                items.getTotalElements(), items.getTotalPages());
    }

    private static SellerOrderListResponse.Row toRow(OrderItem item, Order order) {
        return new SellerOrderListResponse.Row(item.getId(), order.getId(), order.orderNo(),
                order.getPaidAt().atZone(ZONE).toOffsetDateTime(), item.getProductId(),
                item.getProductName(), item.getOptionName(), item.getPrice(), item.getQuantity(),
                item.getStatus().name());
    }
}
