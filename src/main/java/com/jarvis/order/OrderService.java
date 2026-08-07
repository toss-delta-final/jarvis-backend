package com.jarvis.order;

import com.jarvis.address.Address;
import com.jarvis.address.AddressRepository;
import com.jarvis.cart.CartItem;
import com.jarvis.cart.CartService;
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
import com.jarvis.order.dto.UnavailableItemDetail;
import com.jarvis.product.Product;
import com.jarvis.product.ProductOption;
import com.jarvis.product.ProductOptionRepository;
import com.jarvis.product.ProductRepository;
import com.jarvis.product.ProductStockService;
import com.jarvis.recommendation.ConversionAttribution;
import com.jarvis.recommendation.RecommendationAttributionResolver;
import com.jarvis.review.ReviewRepository;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final CartService cartService;
    private final ProductRepository productRepository;
    private final ProductOptionRepository productOptionRepository;
    private final ProductStockService productStockService;
    private final CategoryRepository categoryRepository;
    private final AddressRepository addressRepository;
    private final MemberRepository memberRepository;
    private final ReviewRepository reviewRepository;
    private final PaymentService paymentService;
    private final OrderStatusChanger statusChanger;
    private final RecommendationAttributionResolver attributionResolver;

    private record Line(Product product, ProductOption option, int quantity, CartItem cartItem,
                        ConversionAttribution attribution) {

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
                        line.unitPrice(), line.unitOriginalPrice(), line.quantity(), now,
                        line.attribution()))
                .toList());
        statusChanger.logOrderCreated(order);

        String failureReason = completePayment(order, items, aggregateQuantities(items), request.paymentMethod());
        // 장바구니 경유분만 삭제 — 바로 구매는 장바구니 미접촉 (04 §4)
        List<CartItem> purchasedCartLines = lines.stream().map(Line::cartItem).filter(Objects::nonNull).toList();
        if (order.getStatus() == OrderStatus.PAID && !purchasedCartLines.isEmpty()) {
            cartService.removeOrderedLines(purchasedCartLines);
        }
        return OrderCreateResponse.from(order, failureReason);
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

        String failureReason = completePayment(order, items, aggregateQuantities(items), request.paymentMethod());
        if (order.getStatus() == OrderStatus.PAID) {
            removeMatchingCartLines(memberId, items);
        }
        return OrderCreateResponse.from(order, failureReason);
    }

    /** O-3 — 대표 상태는 enum 코드 8종 (01 §4) */
    public OrderListResponse list(Long memberId, int page, int size) {
        var orders = orderRepository.findAllByMemberIdOrderByIdDesc(memberId, PageRequest.of(page, size));
        List<Long> orderIds = orders.getContent().stream().map(Order::getId).toList();
        Map<Long, List<OrderItem>> itemsByOrder = orderIds.isEmpty() ? Map.of()
                : orderItemRepository.findAllByOrderIdIn(orderIds).stream()
                        .collect(Collectors.groupingBy(OrderItem::getOrderId));
        return OrderListResponse.from(orders, itemsByOrder,
                productsById(itemsByOrder.values().stream().flatMap(List::stream).toList()));
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
        return OrderDetailResponse.from(order, items, productsById(items), reviewedItemIds::contains);
    }

    // ---- 라인 해석 (O-1 두 경로) ----

    private List<Line> resolveLines(Long memberId, OrderCreateRequest request) {
        boolean fromCart = request.cartItemIds() != null && !request.cartItemIds().isEmpty();
        boolean direct = request.items() != null && !request.items().isEmpty();
        if (fromCart == direct) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "cartItemIds 또는 items 중 정확히 하나만 보내야 합니다.");
        }
        List<LineSpec> specs = fromCart ? specsFromCart(memberId, request.cartItemIds())
                : specsFromBody(memberId, request.items());
        Map<Long, Product> products = requireAllAvailable(specs);
        return specs.stream().map(spec -> buildLine(spec, products.get(spec.productId()))).toList();
    }

    /** 옵션 검증 전의 원시 주문 라인 — 두 출처(장바구니/바로 구매)를 같은 모양으로 모은다 */
    private record LineSpec(Long productId, Long optionId, int quantity, CartItem cartItem,
                            ConversionAttribution attribution) {
    }

    /** 추천 출처는 담기 시점(C-2·I-2)에 검증이 끝났으므로 여기선 복사만 한다 (노션 O-1) */
    private List<LineSpec> specsFromCart(Long memberId, List<Long> cartItemIds) {
        return cartService.getOwnedLines(memberId, cartItemIds).stream()
                .map(item -> new LineSpec(item.getProductId(), item.getOptionId(), item.getQuantity(),
                        item, item.attribution()))
                .toList();
    }

    /** 바로 구매는 복사할 원본이 없어 요청값을 검증 후 저장한다 — 실패해도 주문은 정상 처리 (노션 O-1) */
    private List<LineSpec> specsFromBody(Long memberId, List<OrderCreateRequest.OrderLine> orderLines) {
        return orderLines.stream()
                .map(line -> new LineSpec(line.productId(), line.optionId(), line.quantity(), null,
                        // O-1은 로그인 전용이라 게스트 신원이 없다 (02 D30)
                        attributionResolver.resolveForConversion(line.recommendationContext(),
                                line.productId(), memberId, null)))
                .toList();
    }

    /**
     * 상태·재고를 주문 생성 전에 전량 검증한다 (04 §4, 2026-08-05 FE 요청).
     *
     * <p>불량을 만나도 첫 건에서 끊지 않고 모아서 한 번에 400으로 내린다 — 끊으면 사용자가
     * "A 빼고 재시도 → B도 품절 → 또 재시도"를 불량 개수만큼 반복해야 한다.
     *
     * <p>재고는 상품 단위라(02 D33) 같은 상품의 다른 옵션이 여러 줄로 올 수 있다. 줄 단위로 비교하면
     * 합계가 재고를 넘는 걸 놓치므로 상품 단위로 합산해 비교한다.
     */
    private Map<Long, Product> requireAllAvailable(List<LineSpec> specs) {
        Map<Long, Product> products = new LinkedHashMap<>();
        for (LineSpec spec : specs) {
            products.computeIfAbsent(spec.productId(), id -> productRepository.findById(id)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND)));
        }
        Map<Long, Integer> quantities = specs.stream().collect(Collectors.groupingBy(
                LineSpec::productId, Collectors.summingInt(LineSpec::quantity)));
        List<UnavailableItemDetail> unavailable = products.values().stream()
                .map(product -> UnavailableItemDetail.of(product, quantities.get(product.getId())))
                .filter(Objects::nonNull)
                .toList();
        if (!unavailable.isEmpty()) {
            throw new BusinessException(ErrorCode.ORDER_PRODUCT_UNAVAILABLE,
                    Map.of("unavailableItems", unavailable));
        }
        return products;
    }

    /** 옵션 소속 검증 (02 D26①) — 상태·재고는 requireAllAvailable에서 이미 걸렀다 */
    private Line buildLine(LineSpec spec, Product product) {
        List<ProductOption> options = productOptionRepository
                .findAllByProductIdOrderByIdAsc(spec.productId());
        ProductOption option = null;
        if (spec.optionId() == null) {
            if (!options.isEmpty()) {
                throw new BusinessException(ErrorCode.CART_OPTION_REQUIRED);
            }
        } else {
            option = options.stream().filter(o -> o.getId().equals(spec.optionId())).findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.CART_OPTION_INVALID));
        }
        return new Line(product, option, spec.quantity(), spec.cartItem(), spec.attribution());
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

    /** 실패 사유 코드를 돌려준다(성공이면 null) — 로그에만 남기던 값을 응답 failureReason으로도 내보내기 위해 */
    private String completePayment(Order order, List<OrderItem> items,
                                   Map<Long, Integer> quantitiesByProduct, String paymentMethod) {
        PaymentResult result = paymentService.pay(paymentMethod, order.getTotalAmount());
        if (!result.success()) {
            statusChanger.paymentFailed(order, result.failureCode());
            return result.failureCode();
        }
        // 재고 차감은 결제 성공 처리와 같은 트랜잭션의 조건부 UPDATE (02 D33) — 실패 시 OUT_OF_STOCK 결제 실패
        if (!productStockService.deduct(quantitiesByProduct)) {
            statusChanger.paymentFailed(order, OUT_OF_STOCK);
            return OUT_OF_STOCK;
        }
        statusChanger.paymentSucceeded(order, items, LocalDateTime.now());
        return null;
    }

    /** 같은 상품이 여러 라인에 나뉘어 있어도 재고는 한 번에 차감한다. 락 순서는 ProductStockService가 보장 */
    private Map<Long, Integer> aggregateQuantities(List<OrderItem> items) {
        return items.stream().collect(Collectors.groupingBy(OrderItem::getProductId,
                Collectors.summingInt(OrderItem::getQuantity)));
    }

    /** O-2 성공 시 — 장바구니에 같은 상품+옵션 행이 남아 있으면 삭제 (04 §4). 매칭·삭제는 장바구니 소관 */
    private void removeMatchingCartLines(Long memberId, List<OrderItem> items) {
        cartService.removeLinesMatching(memberId, items.stream()
                .map(item -> new CartService.PurchasedLine(item.getProductId(), item.getOptionName()))
                .toList());
    }

    private Order findOwnedOrder(Long memberId, Long orderId) {
        return orderRepository.findById(orderId)
                .filter(order -> order.getMemberId().equals(memberId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }

    /** 표시용 현재 상품 — 이미지와 purchaseState(상세 링크 가능 여부)를 같은 조회로 얻는다 */
    private Map<Long, Product> productsById(List<OrderItem> items) {
        return productRepository
                .findAllById(items.stream().map(OrderItem::getProductId).distinct().toList())
                .stream().collect(Collectors.toMap(Product::getId, p -> p));
    }
}
