package com.jarvis.cart;

import com.jarvis.brand.Brand;
import com.jarvis.brand.BrandRepository;
import com.jarvis.cart.dto.CartAddRequest;
import com.jarvis.cart.dto.CartItemResponse;
import com.jarvis.cart.dto.CartResponse;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.member.GuestService;
import com.jarvis.product.Product;
import com.jarvis.product.ProductOption;
import com.jarvis.product.ProductOptionRepository;
import com.jarvis.product.ProductRepository;
import com.jarvis.product.ProductStatus;
import com.jarvis.product.ProductStock;
import com.jarvis.product.ProductStockRepository;
import com.jarvis.product.PurchaseState;
import com.jarvis.recommendation.ConversionAttribution;
import com.jarvis.recommendation.RecommendationAttributionResolver;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장바구니 (04 §3, 02 D30) — 소유 주체는 회원 XOR 게스트. 로그인 상태면 회원 장바구니만 본다
 * (게스트분은 로그인 시점에 이미 병합 승계됨). 게스트 첫 담기 시 guest 행 INSERT + 쿠키 발급이 한 동작.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CartService {

    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final ProductOptionRepository productOptionRepository;
    private final ProductStockRepository productStockRepository;
    private final BrandRepository brandRepository;
    private final GuestService guestService;
    private final RecommendationAttributionResolver attributionResolver;
    private final CartEventRecorder cartEventRecorder;

    /** C-2 결과 — issuedGuestId가 있으면 컨트롤러가 guest_id 쿠키를 새로 내린다 */
    public record CartAddResult(CartItemResponse item, String issuedGuestId) {
    }

    /** C-1 */
    public CartResponse getCart(Long memberId, String guestId) {
        List<CartItem> cartItems = memberId != null
                ? cartItemRepository.findAllByMemberIdOrderByIdDesc(memberId)
                : guestId != null ? cartItemRepository.findAllByGuestIdOrderByIdDesc(guestId) : List.of();
        if (cartItems.isEmpty()) {
            return CartResponse.of(List.of());
        }
        Map<Long, Product> products = productRepository
                .findAllById(cartItems.stream().map(CartItem::getProductId).distinct().toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, ProductOption> options = productOptionRepository
                .findAllById(cartItems.stream().map(CartItem::getOptionId).filter(id -> id != null).toList())
                .stream().collect(Collectors.toMap(ProductOption::getId, Function.identity()));
        Map<Long, Brand> brands = brandRepository
                .findAllById(products.values().stream().map(Product::getBrandId).distinct().toList())
                .stream().collect(Collectors.toMap(Brand::getId, Function.identity()));
        // 담은 옵션마다 재고가 다르다 (02 D33 개정) — 줄마다 다시 묻지 않도록 상품별로 한 번에 받는다
        Map<Long, Map<Long, Integer>> stocks = stocksByProduct(products.keySet());

        List<CartResponse.Item> items = cartItems.stream().map(cartItem -> {
            Product product = products.get(cartItem.getProductId());
            ProductOption option = cartItem.getOptionId() == null ? null : options.get(cartItem.getOptionId());
            Brand brand = brands.get(product.getBrandId());
            int extra = option == null ? 0 : option.getExtraPrice();
            int stock = stockOf(stocks, product.getId(), cartItem.getOptionId());
            return new CartResponse.Item(cartItem.getId(), product.getId(), product.getName(),
                    brand.getId(), brand.getName(),
                    option == null ? null : option.getId(), option == null ? null : option.getName(),
                    cartItem.getQuantity(), product.getPrice() + extra, product.getOriginalPrice() + extra,
                    product.getImageUrl(), PurchaseState.of(product.getStatus(), stock).name(),
                    Math.min(stock, CartItem.MAX_QUANTITY));
        }).toList();
        return CartResponse.of(items);
    }

    /**
     * C-2 — 동일 상품+옵션 존재 시 수량 합산(상한 99 클램프 — 02 D30과 동일 규칙)
     *
     * @param sessionKey 분석용 세션 키 — 공개 경로는 {@code X-Session-Key} 헤더, 챗봇(I-2)은
     *                   {@code chat:{chatSessionId}} sentinel. 없으면 이벤트만 건너뛴다(노션 C-2)
     */
    @Transactional
    public CartAddResult addItem(Long memberId, String guestId, CartAddRequest request,
                                 String sessionKey) {
        Product product = productRepository.findById(request.productId())
                .filter(p -> p.getStatus() == ProductStatus.ON_SALE)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        validateOption(product.getId(), request.optionId());

        String issuedGuestId = null;
        if (memberId == null) {
            issuedGuestId = guestService.ensureGuest(guestId);
            guestId = issuedGuestId != null ? issuedGuestId : guestId;
        }

        // 잠금 조회(PESSIMISTIC_WRITE) — 유저 직접 담기(C-2)와 챗봇 콜백(I-2)이 같은 라인에 동시
        // 진입해도 consolidate → 상한 검사 → 합산이 직렬화돼 증가분이 유실되지 않는다
        Optional<CartItem> existing = memberId != null
                ? consolidate(cartItemRepository.findMemberLinesForUpdate(memberId, request.productId(), request.optionId()))
                : consolidate(cartItemRepository.findGuestLinesForUpdate(guestId, request.productId(), request.optionId()));
        int resultingQuantity = existing.map(CartItem::getQuantity).orElse(0) + request.quantity();
        // 담기 합산 상한 초과는 400 (노션 C-2, 2026-07-18 확정 — 클램프는 로그인 병합 전용)
        if (resultingQuantity > CartItem.MAX_QUANTITY) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "수량은 최대 " + CartItem.MAX_QUANTITY + "개까지 담을 수 있습니다.");
        }
        // 재고 부족은 400 (합산 후 수량 기준). 최종 방어선은 결제 조건부 차감(02 D33) — 담기 검증은 UX 가드
        requireStock(product.getId(), request.optionId(), resultingQuantity);

        // 추천 출처는 검증 실패해도 담기를 막지 않는다 — 문맥만 버린다 (노션 C-2·I-2 「검증 규칙」)
        ConversionAttribution attribution = attributionResolver.resolveForConversion(
                request.recommendationContext(), request.productId(), memberId, guestId);

        CartItem item;
        if (existing.isPresent()) {
            item = existing.get();
            item.addQuantity(request.quantity());
            item.applyAttribution(attribution);
        } else {
            item = cartItemRepository.save(memberId != null
                    ? CartItem.forMember(memberId, request.productId(), request.optionId(),
                            request.quantity(), attribution)
                    : CartItem.forGuest(guestId, request.productId(), request.optionId(),
                            request.quantity(), attribution));
        }
        // quantity는 이번 요청분(delta)이지 합산 후 총량이 아니다 — 노션 E-1 properties 계약
        cartEventRecorder.record(new CartEventRecorder.CartEvent(
                CartEventRecorder.ADD_EVENT_TYPE, memberId, guestId, sessionKey,
                request.productId(), request.optionId(), request.quantity(),
                unitPrice(product, request.optionId()),
                request.recommendationContext() == null ? null : request.recommendationContext().listId()));
        return new CartAddResult(new CartItemResponse(item.getId(), item.getQuantity()), issuedGuestId);
    }

    /** C-3 — 변경 수량은 합산이 아니라 치환이라 그 값 자체를 재고와 비교 */
    @Transactional
    public CartItemResponse changeQuantity(Long memberId, String guestId, Long cartItemId, int quantity) {
        CartItem item = findOwnedItem(memberId, guestId, cartItemId);
        // 재고를 상품에서 안 읽게 됐어도 존재 확인은 남긴다 — 사라진 상품의 줄은 수량을 못 바꾼다
        if (!productRepository.existsById(item.getProductId())) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        requireStock(item.getProductId(), item.getOptionId(), quantity);
        item.changeQuantity(quantity);
        return new CartItemResponse(item.getId(), item.getQuantity());
    }

    /**
     * C-4 — 삭제 성공(HTTP 200)에만 이벤트를 적재한다. 없는 항목(404)은 미적재다(노션 C-4·I-24):
     * 이 API는 멱등하지 않아 삭제 버튼 연타의 두 번째가 404인데, 그것까지 세면 이벤트가 부풀려진다.
     *
     * <p>적재 재료를 <b>지우기 전에</b> 뽑는다 — 커밋 뒤엔 행이 없어 quantity·optionId를 알 수 없다.
     */
    @Transactional
    public void removeItem(Long memberId, String guestId, Long cartItemId, String sessionKey) {
        CartItem item = findOwnedItem(memberId, guestId, cartItemId);
        // 상품이 그 사이 삭제될 수 있는 경로는 없지만(FK RESTRICT), 이벤트 때문에 삭제를 막지는 않는다
        int price = productRepository.findById(item.getProductId())
                .map(product -> unitPrice(product, item.getOptionId())).orElse(0);
        CartEventRecorder.CartEvent event = new CartEventRecorder.CartEvent(
                CartEventRecorder.REMOVE_EVENT_TYPE, memberId, guestId, sessionKey,
                item.getProductId(), item.getOptionId(),
                // 삭제는 부분 차감이 없어 quantity가 곧 전량이다 (노션 C-4)
                item.getQuantity(), price, item.getListId());

        cartItemRepository.delete(item);
        cartEventRecorder.record(event);
    }

    /** 주문이 지목한 장바구니 라인 (O-1) — 상품·옵션·수량만 필요한 호출자를 위해 행 식별자를 함께 준다 */
    public record PurchasedLine(Long productId, String optionName) {
    }

    /**
     * O-1 장바구니 경유 주문이 읽어가는 라인 — 존재·소유권 검증까지 여기서 한다.
     * "이 행이 이 회원 것인가"는 장바구니 규칙이라 주문이 판단할 일이 아니다.
     *
     * @throws BusinessException 요청 id 중 없는 게 있거나 남의 행이 섞이면 CART_ITEM_NOT_FOUND
     */
    public List<CartItem> getOwnedLines(Long memberId, List<Long> cartItemIds) {
        List<CartItem> cartItems = cartItemRepository.findAllById(cartItemIds);
        boolean allOwned = cartItems.stream().allMatch(item -> memberId.equals(item.getMemberId()));
        if (cartItems.size() != new HashSet<>(cartItemIds).size() || !allOwned) {
            throw new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
        return cartItems;
    }

    /** O-1 장바구니 경유 결제 성공 — 주문에 쓴 행을 그대로 지운다 (04 §4) */
    @Transactional
    public void removeOrderedLines(List<CartItem> orderedLines) {
        cartItemRepository.deleteAll(orderedLines);
    }

    /**
     * O-2 결제 성공 — 주문한 것과 같은 (상품, 옵션)의 장바구니 행이 남아 있으면 지운다 (04 §4).
     * 바로 구매·재결제는 장바구니를 거치지 않아 지울 행 id를 모르므로 내용으로 찾는다.
     *
     * <p>주문 쪽 타입을 받지 않는다 — 장바구니가 주문을 알면 의존이 양방향이 된다.
     */
    @Transactional
    public void removeLinesMatching(Long memberId, List<PurchasedLine> purchased) {
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
                    return purchased.stream().anyMatch(line ->
                            line.productId().equals(cartLine.getProductId())
                                    && Objects.equals(line.optionName(), optionName));
                })
                .toList();
        cartItemRepository.deleteAll(matched);
    }

    /**
     * 게스트 → 회원 병합 승계 (02 D30) — 가입/로그인(A-1/A-2)과 같은 트랜잭션에서 AuthService가 호출.
     * 동일 상품+옵션은 수량 합산(상한 99) 후 게스트 행 삭제, 없으면 소유자만 변경.
     */
    @Transactional
    public void mergeGuestCart(Long memberId, String guestId) {
        for (CartItem guestItem : cartItemRepository.findAllByGuestId(guestId)) {
            Optional<CartItem> memberLine = consolidate(cartItemRepository
                    .findMemberLinesForUpdate(memberId, guestItem.getProductId(), guestItem.getOptionId()));
            if (memberLine.isPresent()) {
                memberLine.get().addQuantity(guestItem.getQuantity());
                // 사라지는 게스트 라인의 추천 출처를 회원 라인이 빈 칸일 때만 물려받는다 (노션 O-1 게스트→회원 병합)
                memberLine.get().inheritAttributionFrom(guestItem);
                cartItemRepository.delete(guestItem);
            } else {
                guestItem.assignTo(memberId);
            }
        }
    }

    /**
     * 동일 상품+옵션 라인 정규화 — 첫 행(가장 오래된 id)만 남기고 나머지는 수량 합산 후 삭제(자가치유).
     *
     * <p>[2026-08-11 · 02 D44] 앞으로 중복은 DB가 막는다 — {@code uk_cart_*}가 {@code option_id}가 아니라
     * 가상 컬럼 {@code option_key}(NULL→0)를 축으로 잡아 무옵션 상품에도 걸린다. 이 메서드는
     * <b>그 마이그레이션 이전에 생긴 중복 행</b>을 위해 남는다. 새로 경합이 나면 잠금 조회가 직렬화하고,
     * 뚫린 경우는 제약 위반 → 409(GlobalExceptionHandler)다.
     */
    private Optional<CartItem> consolidate(List<CartItem> lines) {
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        CartItem head = lines.get(0);
        for (CartItem duplicate : lines.subList(1, lines.size())) {
            head.addQuantity(duplicate.getQuantity());
            cartItemRepository.delete(duplicate);
        }
        return Optional.of(head);
    }

    /** 이벤트 properties의 price — 노션 E-1 계약대로 {@code product.price + product_option.extra_price} */
    private int unitPrice(Product product, Long optionId) {
        if (optionId == null) {
            return product.getPrice();
        }
        return product.getPrice() + productOptionRepository.findById(optionId)
                .map(ProductOption::getExtraPrice).orElse(0);
    }

    /**
     * 재고는 담은 옵션 단위다 (02 D33 개정 — 구 상품 단위 폐기). detail.availableStock는 그 옵션의 남은 수량.
     * 재고 행이 없으면 0으로 본다 — 있어야 할 행이 없는 건 데이터 문제고, 그때 팔면 안 된다.
     */
    private void requireStock(Long productId, Long optionId, int requestedQuantity) {
        int available = productStockRepository.findQuantity(productId, optionId).orElse(0);
        if (requestedQuantity > available) {
            throw new BusinessException(ErrorCode.CART_STOCK_INSUFFICIENT,
                    Map.of("availableStock", available));
        }
    }

    /**
     * 옵션 소속 검증 + 되물음 목록 구성.
     *
     * <p><b>되물음 목록은 구매 가능한 옵션만 담는다</b>(2026-08-09, 노션 C-2·I-2 개정) — 담을 수 없는
     * 걸 제시하면 사용자가 골라도 또 실패한다. 이 목록의 개수가 I-1 {@code optionCount}와 대조되는
     * 정합 가드라, 두 기준이 어긋나면 AI의 자동 선택이 에러도 로그도 없이 죽는다.
     * 품절 포함 전체 목록은 P-2가 옵션별 {@code purchaseState}와 함께 제공한다.
     *
     * <p>남은 옵션이 하나도 없으면 {@code CART_OPTION_REQUIRED}가 아니라
     * {@code CART_STOCK_INSUFFICIENT}다 — 빈 목록을 받은 LLM은 되물을 이름이 없어
     * "옵션을 선택해 주세요: 옵션." 같은 문구를 낸다(AI팀 실측).
     */
    private void validateOption(Long productId, Long optionId) {
        List<ProductOption> options = productOptionRepository.findAllByProductIdOrderByIdAsc(productId);
        if (optionId == null) {
            if (!options.isEmpty()) {
                List<ProductOption> purchasable = purchasableOptions(productId, options);
                if (purchasable.isEmpty()) {
                    throw new BusinessException(ErrorCode.CART_STOCK_INSUFFICIENT,
                            Map.of("availableStock", 0));
                }
                // options 목록을 detail로 동반 — LLM 되물음용 (05 §I-2), FE도 동일 수신
                throw new BusinessException(ErrorCode.CART_OPTION_REQUIRED, Map.of("options",
                        purchasable.stream().map(com.jarvis.cart.dto.CartOptionDetail::from).toList()));
            }
            return;
        }
        boolean belongs = options.stream().anyMatch(option -> option.getId().equals(optionId));
        if (!belongs) {
            throw new BusinessException(ErrorCode.CART_OPTION_INVALID);
        }
    }

    /** 재고가 남은 옵션만. 순서는 원본 그대로 유지한다 — 되물음 순서가 화면 순서와 같아야 한다 */
    private List<ProductOption> purchasableOptions(Long productId, List<ProductOption> options) {
        Map<Long, Integer> byOption = stocksByProduct(java.util.Set.of(productId))
                .getOrDefault(productId, Map.of());
        return options.stream()
                .filter(option -> byOption.getOrDefault(option.getId(), 0) > 0)
                .toList();
    }

    /** productId → (optionId → 수량). 옵션 없는 줄은 키가 null이라 HashMap을 쓴다(Map.of는 null 키 불가) */
    private Map<Long, Map<Long, Integer>> stocksByProduct(java.util.Collection<Long> productIds) {
        Map<Long, Map<Long, Integer>> byProduct = new java.util.HashMap<>();
        for (ProductStock stock : productStockRepository.findAllByProductIdIn(productIds)) {
            byProduct.computeIfAbsent(stock.getProductId(), k -> new java.util.HashMap<>())
                    .put(stock.getOptionId(), stock.getQuantity());
        }
        return byProduct;
    }

    /**
     * 재고 행이 없으면 0. 폴백을 {@code Map.of()}로 두면 안 된다 — 옵션 없는 줄의 키가 null이고
     * 불변 Map은 <b>null 키 조회에서 NPE</b>를 던진다(ImmutableCollections).
     */
    private static int stockOf(Map<Long, Map<Long, Integer>> stocks, Long productId, Long optionId) {
        Map<Long, Integer> byOption = stocks.get(productId);
        if (byOption == null) {
            return 0;
        }
        Integer quantity = byOption.get(optionId);
        return quantity == null ? 0 : quantity;
    }

    private CartItem findOwnedItem(Long memberId, String guestId, Long cartItemId) {
        CartItem item = cartItemRepository.findById(cartItemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND));
        if (!item.isOwnedBy(memberId, guestId)) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        return item;
    }
}
