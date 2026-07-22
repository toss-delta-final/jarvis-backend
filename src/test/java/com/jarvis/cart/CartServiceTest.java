package com.jarvis.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.jarvis.brand.BrandRepository;
import com.jarvis.cart.dto.CartAddRequest;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.member.GuestService;
import com.jarvis.product.Product;
import com.jarvis.product.ProductOption;
import com.jarvis.product.ProductOptionRepository;
import com.jarvis.product.ProductRepository;
import com.jarvis.product.ProductStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock CartItemRepository cartItemRepository;
    @Mock ProductRepository productRepository;
    @Mock ProductOptionRepository productOptionRepository;
    @Mock BrandRepository brandRepository;
    @Mock GuestService guestService;

    @InjectMocks CartService cartService;

    private Product product;

    @BeforeEach
    void setUp() {
        product = mock(Product.class, withSettings().strictness(Strictness.LENIENT));
        when(product.getId()).thenReturn(10L);
        when(product.getStatus()).thenReturn(ProductStatus.ON_SALE);
        when(product.getStockQuantity()).thenReturn(100);        // 시드 기본 재고 (02 D33) — 개별 테스트가 필요 시 override
        lenient().when(productRepository.findById(10L)).thenReturn(Optional.of(product));
        lenient().when(cartItemRepository.save(any(CartItem.class))).thenAnswer(inv -> {
            CartItem item = inv.getArgument(0);
            ReflectionTestUtils.setField(item, "id", 5L);
            return item;
        });
    }

    @Test
    @DisplayName("C-2 — 옵션 있는 상품에 optionId 누락은 CART_OPTION_REQUIRED, 남의 옵션은 CART_OPTION_INVALID")
    void optionValidation() {
        ProductOption option = mock(ProductOption.class);
        when(option.getId()).thenReturn(77L);
        when(productOptionRepository.findAllByProductIdOrderByIdAsc(10L)).thenReturn(List.of(option));

        assertThatThrownBy(() -> cartService.addItem(1L, null, new CartAddRequest(10L, null, 1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CART_OPTION_REQUIRED);
        assertThatThrownBy(() -> cartService.addItem(1L, null, new CartAddRequest(10L, 999L, 1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CART_OPTION_INVALID);
    }

    @Test
    @DisplayName("C-2 — 동일 상품+옵션 재담기는 수량 합산, 합산 99 초과는 400")
    void addQuantityOverflowRejected() {
        when(productOptionRepository.findAllByProductIdOrderByIdAsc(10L)).thenReturn(List.of());
        CartItem existing = CartItem.forMember(1L, 10L, null, 98);
        ReflectionTestUtils.setField(existing, "id", 5L);
        when(cartItemRepository.findMemberLinesForUpdate(1L, 10L, null)).thenReturn(List.of(existing));

        CartService.CartAddResult ok = cartService.addItem(1L, null, new CartAddRequest(10L, null, 1));
        assertThat(ok.item().quantity()).isEqualTo(99);

        assertThatThrownBy(() -> cartService.addItem(1L, null, new CartAddRequest(10L, null, 1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("C-2 — 합산 후 수량이 재고 초과면 CART_STOCK_INSUFFICIENT + availableStock")
    void addStockInsufficient() {
        when(product.getStockQuantity()).thenReturn(3);
        when(productOptionRepository.findAllByProductIdOrderByIdAsc(10L)).thenReturn(List.of());
        when(cartItemRepository.findMemberLinesForUpdate(1L, 10L, null)).thenReturn(List.of());

        assertThatThrownBy(() -> cartService.addItem(1L, null, new CartAddRequest(10L, null, 5)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException ex = (BusinessException) e;
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CART_STOCK_INSUFFICIENT);
                    assertThat(ex.getDetail()).isEqualTo(java.util.Map.of("availableStock", 3));
                });
    }

    @Test
    @DisplayName("C-3 — 변경 수량이 재고 초과면 CART_STOCK_INSUFFICIENT")
    void changeQuantityStockInsufficient() {
        when(product.getStockQuantity()).thenReturn(3);
        CartItem owned = CartItem.forMember(1L, 10L, null, 1);
        ReflectionTestUtils.setField(owned, "id", 5L);
        when(cartItemRepository.findById(5L)).thenReturn(Optional.of(owned));

        assertThatThrownBy(() -> cartService.changeQuantity(1L, null, 5L, 5))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CART_STOCK_INSUFFICIENT);
    }

    @Test
    @DisplayName("C-2 게스트 첫 담기 — guest 행 INSERT + 발급 guestId 반환(쿠키 세팅용)")
    void guestFirstAddIssuesGuest() {
        when(productOptionRepository.findAllByProductIdOrderByIdAsc(10L)).thenReturn(List.of());
        when(cartItemRepository.findGuestLinesForUpdate(any(), any(), any())).thenReturn(List.of());
        when(guestService.ensureGuest(null)).thenReturn("issued-guest-id");

        CartService.CartAddResult result = cartService.addItem(null, null, new CartAddRequest(10L, null, 2));

        assertThat(result.issuedGuestId()).isEqualTo("issued-guest-id");
        verify(guestService).ensureGuest(null);
    }

    @Test
    @DisplayName("병합 승계 — 동일 상품+옵션은 수량 합산 후 게스트 행 삭제, 없으면 소유자 변경 (02 D30)")
    void mergeGuestCart() {
        CartItem guestDup = CartItem.forGuest("g-1", 10L, null, 3);
        CartItem guestNew = CartItem.forGuest("g-1", 20L, null, 1);
        CartItem memberLine = CartItem.forMember(1L, 10L, null, 2);
        when(cartItemRepository.findAllByGuestId("g-1")).thenReturn(List.of(guestDup, guestNew));
        when(cartItemRepository.findMemberLinesForUpdate(1L, 10L, null)).thenReturn(List.of(memberLine));
        when(cartItemRepository.findMemberLinesForUpdate(1L, 20L, null)).thenReturn(List.of());

        cartService.mergeGuestCart(1L, "g-1");

        assertThat(memberLine.getQuantity()).isEqualTo(5);
        verify(cartItemRepository).delete(guestDup);
        assertThat(guestNew.getMemberId()).isEqualTo(1L);
        assertThat(guestNew.getGuestId()).isNull();
    }

    @Test
    @DisplayName("C-3/C-4 — 남의 항목 접근은 403 AUTH_FORBIDDEN")
    void ownershipGuard() {
        CartItem foreign = CartItem.forMember(2L, 10L, null, 1);
        when(cartItemRepository.findById(5L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> cartService.changeQuantity(1L, null, 5L, 3))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_FORBIDDEN);
    }
}
