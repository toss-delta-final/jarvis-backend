package com.jarvis.seller;

import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.order.Order;
import com.jarvis.order.OrderItem;
import com.jarvis.order.OrderItemRepository;
import com.jarvis.order.OrderItemStatus;
import com.jarvis.order.OrderRepository;
import com.jarvis.product.Product;
import com.jarvis.product.ProductRepository;
import com.jarvis.seller.dto.SellerOrderListResponse;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-2 (노션 S-2) — 자사 상품이 포함된 PAID 주문의 주문 단위 목록. 대표상태·tabCounts는 파생값이라
 * order_item 집계 SQL로 뽑고(페이지 orderId·카운트), 페이지 주문들에만 자사 아이템을 붙여 금액·건수·
 * 대표상품·claimStatus를 계산한다. claimStatus는 item.status(claim과 동기 전이)에서 직접 파생.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerOrderService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final List<String> TAB_KEYS = List.of("ORDERED", "SHIPPING", "DELIVERED", "CLAIM");
    private static final Set<String> TAB_FILTERS = Set.copyOf(TAB_KEYS);
    /** 진행 단계 랭크 — 가장 뒤진(작은) 값이 대표상태. *_REQUESTED는 신청 직전 단계와 동급 */
    private static final Map<OrderItemStatus, Integer> PROGRESS_RANK = Map.of(
            OrderItemStatus.ORDERED, 1, OrderItemStatus.CANCEL_REQUESTED, 1,
            OrderItemStatus.SHIPPING, 2,
            OrderItemStatus.DELIVERED, 3, OrderItemStatus.RETURN_REQUESTED, 3,
            OrderItemStatus.CONFIRMED, 4);
    private static final String[] RANK_STATUS = {null, "ORDERED", "SHIPPING", "DELIVERED", "CONFIRMED"};

    private final OrderItemRepository orderItemRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public SellerOrderListResponse list(Long brandId, String statusParam, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.ORDER_INVALID_PARAM);
        }
        String tab = parseTab(statusParam);

        Map<String, Long> rawTabs = orderItemRepository.countSellerOrderTabs(brandId).stream()
                .collect(Collectors.toMap(OrderItemRepository.StatusCountRow::getBucket,
                        OrderItemRepository.StatusCountRow::getCnt, (a, b) -> a));
        long all = rawTabs.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Long> tabCounts = new LinkedHashMap<>();
        tabCounts.put("ALL", all);
        TAB_KEYS.forEach(key -> tabCounts.put(key, rawTabs.getOrDefault(key, 0L)));

        long total = tab == null ? all : tabCounts.getOrDefault(tab, 0L);
        int totalPages = (int) Math.ceil((double) total / size);

        List<Long> orderIds = orderItemRepository.findSellerOrderIdsByTab(
                brandId, tab, size, (long) page * size);
        List<SellerOrderListResponse.Row> rows = orderIds.isEmpty() ? List.of()
                : buildRows(brandId, orderIds);
        return new SellerOrderListResponse(tabCounts, rows, page, size, total, totalPages);
    }

    private List<SellerOrderListResponse.Row> buildRows(Long brandId, List<Long> orderIds) {
        Map<Long, Order> orders = orderRepository.findAllById(orderIds).stream()
                .collect(Collectors.toMap(Order::getId, Function.identity()));
        Map<Long, List<OrderItem>> itemsByOrder = orderItemRepository
                .findSellerItemsByOrderIds(brandId, orderIds).stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
        Set<Long> productIds = itemsByOrder.values().stream().flatMap(List::stream)
                .map(OrderItem::getProductId).collect(Collectors.toSet());
        Map<Long, Product> products = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        // findSellerOrderIdsByTab가 준 최신순을 유지
        return orderIds.stream()
                .map(id -> toRow(orders.get(id), itemsByOrder.getOrDefault(id, List.of()), products))
                .toList();
    }

    private static SellerOrderListResponse.Row toRow(Order order, List<OrderItem> items,
                                                     Map<Long, Product> products) {
        long myItemsAmount = items.stream()
                .filter(SellerOrderService::countsForAmount)
                .mapToLong(i -> (long) i.getPrice() * i.getQuantity())
                .sum();
        OrderItem rep = items.stream()
                .max(Comparator.comparingLong(i -> (long) i.getPrice() * i.getQuantity()))
                .orElseThrow();
        Product repProduct = products.get(rep.getProductId());
        SellerOrderListResponse.RepresentativeProduct representative =
                new SellerOrderListResponse.RepresentativeProduct(rep.getProductId(),
                        repProduct != null ? repProduct.getName() : rep.getProductName(),
                        repProduct != null ? repProduct.getImageUrl() : null,
                        rep.getOptionName());
        return new SellerOrderListResponse.Row(order.getId(), order.orderNo(),
                order.getCreatedAt().atZone(ZONE).toOffsetDateTime(), order.getRecipient(),
                order.getPaymentMethod(), myItemsAmount, items.size(), representative,
                representativeStatus(items), claimStatus(items));
    }

    /** myItemsAmount는 CANCELLED·RETURNED 제외 (노션 S-2) */
    private static boolean countsForAmount(OrderItem item) {
        return item.getStatus() != OrderItemStatus.CANCELLED
                && item.getStatus() != OrderItemStatus.RETURNED;
    }

    /**
     * 대표 상태 (노션 S-2) — 전량 종결(CANCELLED/RETURNED)이면 그 값, 아니면 비종결 아이템 중
     * 가장 뒤진 단계. claimStatus는 별도(status는 진행 단계, 배지 덮어쓰기는 FE).
     */
    private static String representativeStatus(List<OrderItem> items) {
        boolean allTerminal = items.stream().allMatch(i ->
                i.getStatus() == OrderItemStatus.CANCELLED || i.getStatus() == OrderItemStatus.RETURNED);
        if (allTerminal) {
            boolean anyCancelled = items.stream().anyMatch(i -> i.getStatus() == OrderItemStatus.CANCELLED);
            boolean anyReturned = items.stream().anyMatch(i -> i.getStatus() == OrderItemStatus.RETURNED);
            return anyCancelled && !anyReturned ? "CANCELLED" : "RETURNED";
        }
        int minRank = items.stream()
                .filter(SellerOrderService::countsForAmount)
                .mapToInt(i -> PROGRESS_RANK.getOrDefault(i.getStatus(), 4))
                .min().orElse(4);
        return RANK_STATUS[minRank];
    }

    private static String claimStatus(List<OrderItem> items) {
        if (items.stream().anyMatch(i -> i.getStatus() == OrderItemStatus.CANCEL_REQUESTED)) {
            return "CANCEL_REQUESTED";
        }
        if (items.stream().anyMatch(i -> i.getStatus() == OrderItemStatus.RETURN_REQUESTED)) {
            return "RETURN_REQUESTED";
        }
        return null;
    }

    private static String parseTab(String statusParam) {
        if (statusParam == null || statusParam.isBlank()) {
            return null;
        }
        if (!TAB_FILTERS.contains(statusParam)) {
            throw new BusinessException(ErrorCode.ORDER_INVALID_PARAM);
        }
        return statusParam;
    }
}
