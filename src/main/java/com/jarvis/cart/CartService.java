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
import java.util.List;
import java.util.Map;
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
    private final BrandRepository brandRepository;
    private final GuestService guestService;

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

        List<CartResponse.Item> items = cartItems.stream().map(cartItem -> {
            Product product = products.get(cartItem.getProductId());
            ProductOption option = cartItem.getOptionId() == null ? null : options.get(cartItem.getOptionId());
            Brand brand = brands.get(product.getBrandId());
            int extra = option == null ? 0 : option.getExtraPrice();
            return new CartResponse.Item(cartItem.getId(), product.getId(), product.getName(),
                    brand.getId(), brand.getName(),
                    option == null ? null : option.getId(), option == null ? null : option.getName(),
                    cartItem.getQuantity(), product.getPrice() + extra, product.getOriginalPrice() + extra,
                    product.getImageUrl(), product.isPurchasable());
        }).toList();
        return CartResponse.of(items);
    }

    /** C-2 — 동일 상품+옵션 존재 시 수량 합산(상한 99 클램프 — 02 D30과 동일 규칙) */
    @Transactional
    public CartAddResult addItem(Long memberId, String guestId, CartAddRequest request) {
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
        requireStock(product, resultingQuantity);

        CartItem item;
        if (existing.isPresent()) {
            item = existing.get();
            item.addQuantity(request.quantity());
        } else {
            item = cartItemRepository.save(memberId != null
                    ? CartItem.forMember(memberId, request.productId(), request.optionId(), request.quantity())
                    : CartItem.forGuest(guestId, request.productId(), request.optionId(), request.quantity()));
        }
        return new CartAddResult(new CartItemResponse(item.getId(), item.getQuantity()), issuedGuestId);
    }

    /** C-3 — 변경 수량은 합산이 아니라 치환이라 그 값 자체를 재고와 비교 */
    @Transactional
    public CartItemResponse changeQuantity(Long memberId, String guestId, Long cartItemId, int quantity) {
        CartItem item = findOwnedItem(memberId, guestId, cartItemId);
        Product product = productRepository.findById(item.getProductId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        requireStock(product, quantity);
        item.changeQuantity(quantity);
        return new CartItemResponse(item.getId(), item.getQuantity());
    }

    /** C-4 */
    @Transactional
    public void removeItem(Long memberId, String guestId, Long cartItemId) {
        cartItemRepository.delete(findOwnedItem(memberId, guestId, cartItemId));
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
                cartItemRepository.delete(guestItem);
            } else {
                guestItem.assignTo(memberId);
            }
        }
    }

    /**
     * 동일 상품+옵션 라인 정규화 — 첫 행(가장 오래된 id)만 남기고 나머지는 수량 합산 후 삭제(자가치유).
     * 무옵션(option_id NULL) 상품의 동시 담기 경합으로 중복 행이 생겨도 다음 담기 때 한 행으로 수렴한다.
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

    /** 재고는 상품 단위(02 D33) — 옵션별 재고 없음. detail.availableStock로 남은 수량 동반 */
    private void requireStock(Product product, int requestedQuantity) {
        if (requestedQuantity > product.getStockQuantity()) {
            throw new BusinessException(ErrorCode.CART_STOCK_INSUFFICIENT,
                    Map.of("availableStock", product.getStockQuantity()));
        }
    }

    private void validateOption(Long productId, Long optionId) {
        List<ProductOption> options = productOptionRepository.findAllByProductIdOrderByIdAsc(productId);
        if (optionId == null) {
            if (!options.isEmpty()) {
                // options 목록을 detail로 동반 — LLM 되물음용 (05 §I-2), FE도 동일 수신
                throw new BusinessException(ErrorCode.CART_OPTION_REQUIRED, Map.of("options",
                        options.stream().map(com.jarvis.cart.dto.CartOptionDetail::from).toList()));
            }
            return;
        }
        boolean belongs = options.stream().anyMatch(option -> option.getId().equals(optionId));
        if (!belongs) {
            throw new BusinessException(ErrorCode.CART_OPTION_INVALID);
        }
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
