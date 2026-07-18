package com.jarvis.cart;

import com.jarvis.brand.Brand;
import com.jarvis.brand.BrandRepository;
import com.jarvis.cart.dto.CartAddRequest;
import com.jarvis.cart.dto.CartItemResponse;
import com.jarvis.cart.dto.CartResponse;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.member.Guest;
import com.jarvis.member.GuestRepository;
import com.jarvis.product.Product;
import com.jarvis.product.ProductOption;
import com.jarvis.product.ProductOptionRepository;
import com.jarvis.product.ProductRepository;
import com.jarvis.product.ProductStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
    private final GuestRepository guestRepository;

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
            issuedGuestId = ensureGuest(guestId);
            guestId = issuedGuestId != null ? issuedGuestId : guestId;
        }

        Optional<CartItem> existing = memberId != null
                ? consolidate(cartItemRepository.findMemberLines(memberId, request.productId(), request.optionId()))
                : consolidate(cartItemRepository.findGuestLines(guestId, request.productId(), request.optionId()));
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

    /** C-3 */
    @Transactional
    public CartItemResponse changeQuantity(Long memberId, String guestId, Long cartItemId, int quantity) {
        CartItem item = findOwnedItem(memberId, guestId, cartItemId);
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
                    .findMemberLines(memberId, guestItem.getProductId(), guestItem.getOptionId()));
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

    /** 게스트 담기 — 쿠키가 없거나 guest 행이 없으면(INSERT 동반 — 03 D3) 발급/복구 */
    private String ensureGuest(String guestId) {
        if (guestId != null && guestRepository.existsById(guestId)) {
            return null;
        }
        String id = guestId != null ? guestId : UUID.randomUUID().toString();
        guestRepository.save(Guest.issue(id));
        return id;
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
