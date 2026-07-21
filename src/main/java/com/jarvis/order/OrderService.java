package com.jarvis.order;

import com.jarvis.address.Address;
import com.jarvis.address.AddressRepository;
import com.jarvis.cart.CartItem;
import com.jarvis.cart.CartItemRepository;
import com.jarvis.category.Category;
import com.jarvis.category.CategoryRepository;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.member.MemberRepository;
import com.jarvis.order.PaymentService.PaymentResult;
import com.jarvis.order.dto.InternalOrderListResponse;
import com.jarvis.order.dto.InternalOrderStatusResponse;
import com.jarvis.order.dto.OrderCreateRequest;
import com.jarvis.order.dto.OrderCreateResponse;
import com.jarvis.order.dto.OrderDetailResponse;
import com.jarvis.order.dto.OrderListResponse;
import com.jarvis.order.dto.RetryPaymentRequest;
import com.jarvis.product.Product;
import com.jarvis.product.ProductChangeLog;
import com.jarvis.product.ProductChangeLogRepository;
import com.jarvis.product.ProductChangeType;
import com.jarvis.product.ProductOption;
import com.jarvis.product.ProductOptionRepository;
import com.jarvis.product.ProductRepository;
import com.jarvis.product.ProductStatus;
import com.jarvis.review.ReviewRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O-1~O-4 (04 §4) — 장바구니 경유(cartItemIds)와 바로 구매(items)는 라인 출처만 다르고
 * 스냅샷·검증·결제·전이는 같은 코드로 수렴. 결제 금액은 서버 스냅샷 재계산이 유일한 진실 (02 D26④).
 * 상태 전이·로그는 전부 OrderStatusChanger 경유 (01 D12).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    static final String OUT_OF_STOCK = "OUT_OF_STOCK";

    private static final int CHAT_LIST_LIMIT = 20; // I-4/I-19 조회 상한 (05 — 챗봇 인용용, 페이지네이션 없음)
    private static final Set<String> CHAT_STATUS_VOCAB = Set.of(
            "ORDERED", "SHIPPING", "DELIVERED", "CONFIRMED", "CANCELLED", "RETURNED");

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final ProductOptionRepository productOptionRepository;
    private final ProductChangeLogRepository productChangeLogRepository;
    private final CategoryRepository categoryRepository;
    private final AddressRepository addressRepository;
    private final MemberRepository memberRepository;
    private final ReviewRepository reviewRepository;
    private final PaymentService paymentService;
    private final OrderStatusChanger statusChanger;

    private record Line(Product product, ProductOption option, int quantity, CartItem cartItem) {

        int unitPrice() {
            return product.getPrice() + (option == null ? 0 : option.getExtraPrice());
        }

        int unitOriginalPrice() {
            return product.getOriginalPrice() + (option == null ? 0 : option.getExtraPrice());
        }
    }

    private record Shipping(String recipient, String phone, String zipCode,
                            String address1, String address2) {
    }

    /** O-1 — PENDING 생성(아이템도 PENDING — 01 D9) → 스냅샷 복사 → mock 결제 → PAID/PAYMENT_FAILED */
    @Transactional
    public OrderCreateResponse create(Long memberId, OrderCreateRequest request) {
        List<Line> lines = resolveLines(memberId, request);
        Shipping shipping = resolveShipping(memberId, request);
        // long으로 합산 후 INT 컬럼 상한 검증 — 크롤링 고가 상품 × 수량으로 int 곱셈이 넘치면 금액이 음수가 된다 (02 D26④)
        long total = lines.stream().mapToLong(line -> (long) line.unitPrice() * line.quantity()).sum();
        if (total > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "주문 금액이 처리 가능한 범위를 초과했습니다.");
        }
        int totalAmount = (int) total;

        Order order = orderRepository.save(Order.create(memberId, request.paymentMethod(), totalAmount,
                shipping.recipient(), shipping.phone(), shipping.zipCode(),
                shipping.address1(), shipping.address2(), request.deliveryRequest()));
        LocalDateTime now = LocalDateTime.now();
        List<OrderItem> items = orderItemRepository.saveAll(lines.stream()
                .map(line -> OrderItem.pending(order.getId(), line.product().getId(),
                        line.product().getName(), line.option() == null ? null : line.option().getName(),
                        line.unitPrice(), line.unitOriginalPrice(), line.quantity(), now))
                .toList());
        statusChanger.logOrderCreated(order);

        completePayment(order, items, aggregateQuantities(items), request.paymentMethod());
        // 장바구니 경유분만 삭제 — 바로 구매는 장바구니 미접촉 (04 §4)
        List<CartItem> purchasedCartLines = lines.stream().map(Line::cartItem).filter(Objects::nonNull).toList();
        if (order.getStatus() == OrderStatus.PAID && !purchasedCartLines.isEmpty()) {
            cartItemRepository.deleteAll(purchasedCartLines);
        }
        return OrderCreateResponse.from(order);
    }

    /** O-2 — 실패 주문 재결제. 성공 부수효과는 O-1의 PAID와 동일 (04 §4) */
    @Transactional
    public OrderCreateResponse retryPayment(Long memberId, Long orderId, RetryPaymentRequest request) {
        // 비관적 락으로 동시 재결제 직렬화 — 락 안에서 상태를 재확인해 이중 차감·PAID 로그 2행을 막는다 (01 §2-1)
        Order order = orderRepository.findByIdForUpdate(orderId)
                .filter(o -> o.getMemberId().equals(memberId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        if (!order.isRetryable()) {
            throw new BusinessException(ErrorCode.ORDER_INVALID_TRANSITION);
        }
        order.changePaymentMethod(request.paymentMethod());
        List<OrderItem> items = orderItemRepository.findAllByOrderId(orderId);

        completePayment(order, items, aggregateQuantities(items), request.paymentMethod());
        if (order.getStatus() == OrderStatus.PAID) {
            removeMatchingCartLines(memberId, items);
        }
        return OrderCreateResponse.from(order);
    }

    /** O-3 — 대표 상태는 enum 코드 8종 (01 §4) */
    public OrderListResponse list(Long memberId, int page, int size) {
        var orders = orderRepository.findAllByMemberIdOrderByIdDesc(memberId, PageRequest.of(page, size));
        List<Long> orderIds = orders.getContent().stream().map(Order::getId).toList();
        Map<Long, List<OrderItem>> itemsByOrder = orderIds.isEmpty() ? Map.of()
                : orderItemRepository.findAllByOrderIdIn(orderIds).stream()
                        .collect(Collectors.groupingBy(OrderItem::getOrderId));
        return OrderListResponse.from(orders, itemsByOrder,
                imageUrls(itemsByOrder.values().stream().flatMap(List::stream).toList()));
    }

    /** I-4 — 최근 주문 상태 요약 (05 §I-4) — 문의 챗봇 전용, I-19(목록)와 역할 분담 */
    public InternalOrderStatusResponse statusSummary(Long memberId, int recent) {
        requireMember(memberId);
        var orders = orderRepository.findAllByMemberIdOrderByIdDesc(
                memberId, PageRequest.of(0, Math.min(Math.max(recent, 1), CHAT_LIST_LIMIT)));
        return InternalOrderStatusResponse.from(orders.getContent(), itemsByOrder(orders.getContent()));
    }

    /** I-19 — CS 챗봇 구매 이력 목록 (05 §I-19) — status는 우리 상태명 단일 필터(아이템 기준) */
    public InternalOrderListResponse listForChat(Long memberId, String status) {
        requireMember(memberId);
        OrderItemStatus filter = parseChatStatus(status);
        var orders = orderRepository.findAllByMemberIdOrderByIdDesc(
                memberId, PageRequest.of(0, CHAT_LIST_LIMIT));
        Map<Long, List<OrderItem>> itemsByOrder = itemsByOrder(orders.getContent());
        List<Order> filtered = filter == null ? orders.getContent()
                : orders.getContent().stream()
                        .filter(order -> itemsByOrder.getOrDefault(order.getId(), List.<OrderItem>of())
                                .stream().anyMatch(item -> item.getStatus() == filter))
                        .toList();
        return InternalOrderListResponse.from(filtered, itemsByOrder, categoryNamesByProduct(itemsByOrder));
    }

    /** I-19 categoryName(노션 응답 필드) — 아이템 상품의 소분류명. 상품 삭제 정책이 RESTRICT라 항상 존재 */
    private Map<Long, String> categoryNamesByProduct(Map<Long, List<OrderItem>> itemsByOrder) {
        List<Long> productIds = itemsByOrder.values().stream()
                .flatMap(List::stream).map(OrderItem::getProductId).distinct().toList();
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> categoryByProduct = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Product::getCategoryId));
        Map<Long, String> categoryNames = categoryRepository
                .findAllById(categoryByProduct.values().stream().distinct().toList()).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
        return categoryByProduct.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> categoryNames.get(entry.getValue())));
    }

    private Map<Long, List<OrderItem>> itemsByOrder(List<Order> orders) {
        List<Long> orderIds = orders.stream().map(Order::getId).toList();
        return orderIds.isEmpty() ? Map.of()
                : orderItemRepository.findAllByOrderIdIn(orderIds).stream()
                        .collect(Collectors.groupingBy(OrderItem::getOrderId));
    }

    /** I-19 status 어휘 (05 §I-19) — 우리 상태명 6종만 허용, 그 외 400 */
    private static OrderItemStatus parseChatStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        if (!CHAT_STATUS_VOCAB.contains(status)) {
            throw new BusinessException(ErrorCode.ORDER_INVALID_PARAM);
        }
        return OrderItemStatus.valueOf(status);
    }

    /** I-4·I-19 — 미존재 회원은 200 빈 목록이 아니라 404 (05 §I-4·§I-19) */
    private void requireMember(Long memberId) {
        if (!memberRepository.existsById(memberId)) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND);
        }
    }

    /** O-4 — 가능 액션(canCancel/canReturn/canReview)은 01 §3 매트릭스를 서버가 계산 */
    public OrderDetailResponse detail(Long memberId, Long orderId) {
        Order order = findOwnedOrder(memberId, orderId);
        List<OrderItem> items = orderItemRepository.findAllByOrderId(orderId);
        Set<Long> reviewedItemIds = new HashSet<>(reviewRepository.findOrderItemIdsIn(
                items.stream().map(OrderItem::getId).toList()));
        return OrderDetailResponse.from(order, items, imageUrls(items), reviewedItemIds::contains);
    }

    // ---- 라인 해석 (O-1 두 경로) ----

    private List<Line> resolveLines(Long memberId, OrderCreateRequest request) {
        boolean fromCart = request.cartItemIds() != null && !request.cartItemIds().isEmpty();
        boolean direct = request.items() != null && !request.items().isEmpty();
        if (fromCart == direct) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "cartItemIds 또는 items 중 정확히 하나만 보내야 합니다.");
        }
        return fromCart ? linesFromCart(memberId, request.cartItemIds()) : linesFromBody(request.items());
    }

    private List<Line> linesFromCart(Long memberId, List<Long> cartItemIds) {
        List<CartItem> cartItems = cartItemRepository.findAllById(cartItemIds);
        boolean allOwned = cartItems.stream()
                .allMatch(item -> memberId.equals(item.getMemberId()));
        if (cartItems.size() != new HashSet<>(cartItemIds).size() || !allOwned) {
            throw new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
        return cartItems.stream()
                .map(item -> buildLine(item.getProductId(), item.getOptionId(), item.getQuantity(), item))
                .toList();
    }

    private List<Line> linesFromBody(List<OrderCreateRequest.OrderLine> orderLines) {
        return orderLines.stream()
                .map(line -> buildLine(line.productId(), line.optionId(), line.quantity(), null))
                .toList();
    }

    /** 검증은 두 경로 공통 (04 §4) — ON_SALE 아니면 400, 옵션 소속 검증 (02 D26①) */
    private Line buildLine(Long productId, Long optionId, int quantity, CartItem cartItem) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        if (product.getStatus() != ProductStatus.ON_SALE) {
            throw new BusinessException(ErrorCode.ORDER_PRODUCT_UNAVAILABLE);
        }
        List<ProductOption> options = productOptionRepository.findAllByProductIdOrderByIdAsc(productId);
        ProductOption option = null;
        if (optionId == null) {
            if (!options.isEmpty()) {
                throw new BusinessException(ErrorCode.CART_OPTION_REQUIRED);
            }
        } else {
            option = options.stream().filter(o -> o.getId().equals(optionId)).findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.CART_OPTION_INVALID));
        }
        return new Line(product, option, quantity, cartItem);
    }

    private Shipping resolveShipping(Long memberId, OrderCreateRequest request) {
        boolean byId = request.addressId() != null;
        boolean byInput = request.address() != null;
        if (byId == byInput) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "addressId 또는 address 중 정확히 하나만 보내야 합니다.");
        }
        if (byId) {
            Address address = addressRepository.findById(request.addressId())
                    .filter(a -> a.getMemberId().equals(memberId))
                    .orElseThrow(() -> new BusinessException(ErrorCode.ADDRESS_NOT_FOUND));
            return new Shipping(address.getRecipient(), address.getPhone(), address.getZipCode(),
                    address.getAddress1(), address.getAddress2());
        }
        OrderCreateRequest.AddressInput input = request.address();
        return new Shipping(input.recipient(), input.phone(), input.zipCode(),
                input.address1(), input.address2());
    }

    // ---- 결제 (O-1·O-2 공통) ----

    private void completePayment(Order order, List<OrderItem> items,
                                 Map<Long, Integer> quantitiesByProduct, String paymentMethod) {
        PaymentResult result = paymentService.pay(paymentMethod, order.getTotalAmount());
        if (!result.success()) {
            statusChanger.paymentFailed(order, result.failureCode());
            return;
        }
        // 재고 차감은 결제 성공 처리와 같은 트랜잭션의 조건부 UPDATE (02 D33) — 실패 시 OUT_OF_STOCK 결제 실패
        if (!deductStock(quantitiesByProduct)) {
            statusChanger.paymentFailed(order, OUT_OF_STOCK);
            return;
        }
        statusChanger.paymentSucceeded(order, items, LocalDateTime.now());
    }

    private Map<Long, Integer> aggregateQuantities(List<OrderItem> items) {
        // productId 오름차순 고정 — 동시 주문 간 락 순서 통일(데드락 방지)
        return items.stream().collect(Collectors.groupingBy(OrderItem::getProductId, TreeMap::new,
                Collectors.summingInt(OrderItem::getQuantity)));
    }

    private boolean deductStock(Map<Long, Integer> quantitiesByProduct) {
        List<Map.Entry<Long, Integer>> applied = new ArrayList<>();
        // 품절 로그는 버퍼에 모았다가 전 품목 차감 성공 후에만 저장 — 일부 실패로 보상 복원되면 허위 품절 로그가 남지 않도록 (02 D32·D33)
        List<ProductChangeLog> stockOutLogs = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : quantitiesByProduct.entrySet()) {
            if (productRepository.deductStock(entry.getKey(), entry.getValue()) == 0) {
                applied.forEach(done -> productRepository.restoreStock(done.getKey(), done.getValue()));
                return false;
            }
            applied.add(entry);
            if (productRepository.findStockQuantity(entry.getKey()).orElse(-1) == 0) {
                // 주문에 의한 재고 -1은 미기록, 품절 전환(new_value=0)만 기록 (02 D32·D33)
                stockOutLogs.add(ProductChangeLog.of(entry.getKey(), ProductChangeType.STOCK,
                        String.valueOf(entry.getValue()), "0"));
            }
        }
        if (!stockOutLogs.isEmpty()) {
            productChangeLogRepository.saveAll(stockOutLogs);
        }
        return true;
    }

    /** O-2 성공 시 — 장바구니에 같은 상품+옵션 행이 남아 있으면 삭제 (04 §4) */
    private void removeMatchingCartLines(Long memberId, List<OrderItem> items) {
        List<CartItem> cartLines = cartItemRepository.findAllByMemberId(memberId);
        if (cartLines.isEmpty()) {
            return;
        }
        Map<Long, String> optionNames = productOptionRepository
                .findAllById(cartLines.stream().map(CartItem::getOptionId).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(ProductOption::getId, ProductOption::getName));
        List<CartItem> matched = cartLines.stream()
                .filter(cartLine -> {
                    String optionName = cartLine.getOptionId() == null ? null
                            : optionNames.get(cartLine.getOptionId());
                    return items.stream().anyMatch(item ->
                            item.getProductId().equals(cartLine.getProductId())
                                    && Objects.equals(item.getOptionName(), optionName));
                })
                .toList();
        cartItemRepository.deleteAll(matched);
    }

    private Order findOwnedOrder(Long memberId, Long orderId) {
        return orderRepository.findById(orderId)
                .filter(order -> order.getMemberId().equals(memberId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }

    private Map<Long, String> imageUrls(List<OrderItem> items) {
        return productRepository
                .findAllById(items.stream().map(OrderItem::getProductId).distinct().toList())
                .stream().collect(Collectors.toMap(Product::getId, Product::getImageUrl));
    }
}
