package com.jarvis.seller;

import com.jarvis.brand.BrandRepository;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.order.Order;
import com.jarvis.order.OrderItem;
import com.jarvis.order.OrderItemRepository;
import com.jarvis.order.OrderItemStatus;
import com.jarvis.order.OrderRepository;
import com.jarvis.order.OrderStatusChanger;
import com.jarvis.product.Product;
import com.jarvis.product.ProductRepository;
import com.jarvis.seller.dto.SellerOrderInternalResponse;
import com.jarvis.seller.dto.SellerOrderListResponse;
import com.jarvis.seller.dto.SellerShipRequest;
import com.jarvis.seller.dto.SellerShipResponse;
import java.time.LocalDateTime;
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
    private final BrandRepository brandRepository;
    private final OrderStatusChanger statusChanger;

    public SellerOrderListResponse list(Long brandId, String statusParam, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.ORDER_INVALID_PARAM);
        }
        String tab = parseTab(statusParam);

        Map<String, Long> tabCounts = tabCounts(brandId, null, null, null);
        long all = tabCounts.get("ALL");
        long total = tab == null ? all : tabCounts.getOrDefault(tab, 0L);
        int totalPages = (int) Math.ceil((double) total / size);

        List<Long> orderIds = orderItemRepository.findSellerOrderIdsByTab(
                brandId, tab, null, null, null, size, (long) page * size);
        List<SellerOrderListResponse.Row> rows = orderIds.isEmpty() ? List.of()
                : buildRows(brandId, orderIds);
        return new SellerOrderListResponse(tabCounts, rows, page, size, total, totalPages);
    }

    /**
     * I-29 (노션 I-29) — S-2와 같은 파생을 쓰되 응답은 items 배열을 실은 internal 판이다.
     * 파라미터 검증은 컨트롤러의 @Pattern/@Min/@Max가 맡아 VALIDATION_ERROR로 떨어진다
     * (S-2의 parseTab은 ORDER_INVALID_PARAM을 던지므로 여기서 재사용하지 않는다 — 노션 I-29 결정 1).
     * 기간을 주면 tabCounts도 그 기간 기준이다 — 탭 선택만 무시하는 게 "전량 기준"의 뜻이기 때문.
     */
    public SellerOrderInternalResponse listInternal(Long brandId, String tab, Long orderId,
                                                    AnalysisPeriod period, int limit, long offset) {
        if (!brandRepository.existsById(brandId)) {
            throw new BusinessException(ErrorCode.BRAND_NOT_FOUND);
        }
        LocalDateTime from = period.from() == null ? null : period.from().atStartOfDay();
        // to는 그날을 포함해야 하므로 다음날 0시 미만으로 연다(쿼리는 < :to)
        LocalDateTime to = period.to() == null ? null : period.to().plusDays(1).atStartOfDay();

        Map<String, Long> tabCounts = tabCounts(brandId, orderId, from, to);
        long total = tab == null ? tabCounts.get("ALL") : tabCounts.getOrDefault(tab, 0L);

        List<Long> orderIds = orderItemRepository.findSellerOrderIdsByTab(
                brandId, tab, orderId, from, to, limit, offset);
        List<SellerOrderInternalResponse.Row> rows = orderIds.isEmpty() ? List.of()
                : buildInternalRows(brandId, orderIds);
        return new SellerOrderInternalResponse(tabCounts, rows, total);
    }

    /**
     * I-30 발송 처리 (노션 I-30) — HITL confirm 후에만 호출된다. 상태 변경은 OrderStatusChanger
     * 단일 관문을 통한다(01 D12). brandId는 티켓 claim에서 오지만 신뢰하지 않고 실행 시점에 재검증한다.
     *
     * <p>판정 순서가 계약이다: 어휘 밖 → VALIDATION_ERROR, 소유 아님·미존재 → 404(존재 은닉),
     * 이미 SHIPPING → 409, 그 외 ORDERED 아님 → 400. 409를 400보다 먼저 봐야 "이미 발송됨"과
     * "발송할 수 없는 상태"가 구분된다.
     */
    @Transactional
    public SellerShipResponse shipItem(Long brandId, Long orderItemId, SellerShipRequest request) {
        OrderItemStatus toStatus = parseItemStatus(request.toStatus());
        if (toStatus != OrderItemStatus.SHIPPING) {
            // 어휘엔 있으나 MVP 허용 전이가 아니다 — DELIVERED·CONFIRMED는 시스템 모의 전이 소관
            throw new BusinessException(ErrorCode.ORDER_INVALID_TRANSITION);
        }
        OrderItem item = orderItemRepository.findOwnedByBrand(orderItemId, brandId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_ITEM_NOT_FOUND));
        if (item.getStatus() == OrderItemStatus.SHIPPING) {
            throw new BusinessException(ErrorCode.ORDER_ALREADY_SHIPPED);
        }
        if (item.getStatus() != OrderItemStatus.ORDERED) {
            // 활성 클레임(CANCEL_REQUESTED)도 여기 걸린다 — status 자체가 ORDERED가 아니므로
            throw new BusinessException(ErrorCode.ORDER_INVALID_TRANSITION);
        }

        LocalDateTime now = LocalDateTime.now();
        if (!statusChanger.shipBySeller(item, request.reason(), now)) {
            // 조건부 UPDATE가 0건 — 위 검사 이후 다른 실행이 먼저 가져갔다(이중 발송 경합)
            throw new BusinessException(ErrorCode.ORDER_ALREADY_SHIPPED);
        }
        return new SellerShipResponse(item.getId(), OrderItemStatus.ORDERED.name(),
                OrderItemStatus.SHIPPING.name(), now.atZone(ZONE).toOffsetDateTime());
    }

    /** 상태기계(01) 어휘 밖이면 VALIDATION_ERROR — PREPARING·SHIPPED가 여기 걸린다(노션 I-30) */
    private static OrderItemStatus parseItemStatus(String toStatus) {
        try {
            return OrderItemStatus.valueOf(toStatus);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    /** ALL + 4탭 키를 항상 채운다 — 0건도 키가 있어야 에이전트가 "없음"을 말할 수 있다(노션 I-29). */
    private Map<String, Long> tabCounts(Long brandId, Long orderId, LocalDateTime from, LocalDateTime to) {
        Map<String, Long> raw = orderItemRepository.countSellerOrderTabs(brandId, orderId, from, to).stream()
                .collect(Collectors.toMap(OrderItemRepository.StatusCountRow::getBucket,
                        OrderItemRepository.StatusCountRow::getCnt, (a, b) -> a));
        Map<String, Long> tabCounts = new LinkedHashMap<>();
        tabCounts.put("ALL", raw.values().stream().mapToLong(Long::longValue).sum());
        TAB_KEYS.forEach(key -> tabCounts.put(key, raw.getOrDefault(key, 0L)));
        return tabCounts;
    }

    private List<SellerOrderInternalResponse.Row> buildInternalRows(Long brandId, List<Long> orderIds) {
        Map<Long, Order> orders = orderRepository.findAllById(orderIds).stream()
                .collect(Collectors.toMap(Order::getId, Function.identity()));
        Map<Long, List<OrderItem>> itemsByOrder = orderItemRepository
                .findSellerItemsByOrderIds(brandId, orderIds).stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
        Set<Long> productIds = itemsByOrder.values().stream().flatMap(List::stream)
                .map(OrderItem::getProductId).collect(Collectors.toSet());
        Map<Long, Product> products = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        return orderIds.stream()
                .map(id -> toInternalRow(orders.get(id), itemsByOrder.getOrDefault(id, List.of()), products))
                .toList();
    }

    private static SellerOrderInternalResponse.Row toInternalRow(Order order, List<OrderItem> items,
                                                                 Map<Long, Product> products) {
        long myItemsAmount = items.stream()
                .filter(SellerOrderService::countsForAmount)
                .mapToLong(i -> (long) i.getPrice() * i.getQuantity())
                .sum();
        List<SellerOrderInternalResponse.Item> itemRows = items.stream()
                .map(item -> {
                    Product product = products.get(item.getProductId());
                    return new SellerOrderInternalResponse.Item(item.getId(), item.getProductId(),
                            // 현재 상품명 우선 + 스냅샷 폴백 — S-2 대표상품과 같은 규칙
                            product != null ? product.getName() : item.getProductName(),
                            item.getOptionName(), item.getQuantity(), item.getPrice(),
                            item.getStatus().name(), activeClaimStatus(item));
                })
                .toList();
        return new SellerOrderInternalResponse.Row(order.getId(), order.orderNo(),
                order.getCreatedAt().atZone(ZONE).toOffsetDateTime(), order.getRecipient(),
                order.getPaymentMethod(), myItemsAmount,
                representativeStatus(items), claimStatus(items), itemRows);
    }

    /** 아이템 단위 활성 클레임 — 종결된 클레임은 status 자체에 드러나므로 null (노션 I-29) */
    private static String activeClaimStatus(OrderItem item) {
        return item.getStatus() == OrderItemStatus.CANCEL_REQUESTED
                || item.getStatus() == OrderItemStatus.RETURN_REQUESTED
                ? item.getStatus().name() : null;
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
