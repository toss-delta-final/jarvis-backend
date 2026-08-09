package com.jarvis.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.jarvis.recommendation.ConversionAttribution;
import com.jarvis.recommendation.RecommendationAttributionResolver;
import com.jarvis.recommendation.dto.RecommendationContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    @Mock com.jarvis.product.ProductStockRepository productStockRepository;
    @Mock BrandRepository brandRepository;
    @Mock GuestService guestService;
    @Mock RecommendationAttributionResolver attributionResolver;
    @Mock CartEventRecorder cartEventRecorder;

    @InjectMocks CartService cartService;

    private Product product;

    @BeforeEach
    void setUp() {
        product = mock(Product.class, withSettings().strictness(Strictness.LENIENT));
        when(product.getId()).thenReturn(10L);
        when(product.getStatus()).thenReturn(ProductStatus.ON_SALE);
        // 재고는 상품이 아니라 product_stock에 있다 (02 D33 개정) — 개별 테스트가 필요 시 override
        lenient().when(productStockRepository.findQuantity(anyLong(), any())).thenReturn(Optional.of(100));
        lenient().when(productRepository.existsById(10L)).thenReturn(true);
        lenient().when(productRepository.findById(10L)).thenReturn(Optional.of(product));
        // 담기 대부분은 추천 경유가 아니다 — 개별 테스트가 필요할 때만 override
        lenient().when(attributionResolver.resolveForConversion(any(), any(), any(), any()))
                .thenReturn(ConversionAttribution.NONE);
        lenient().when(cartItemRepository.save(any(CartItem.class))).thenAnswer(inv -> {
            CartItem item = inv.getArgument(0);
            ReflectionTestUtils.setField(item, "id", 5L);
            return item;
        });
    }

    @Test
    @DisplayName("C-1 — maxQuantity는 재고와 담기 상한 99 중 작은 값 (FE가 99를 다시 계산하지 않도록)")
    void getCartExposesMaxQuantity() {
        com.jarvis.brand.Brand brand = mock(com.jarvis.brand.Brand.class, withSettings().strictness(Strictness.LENIENT));
        when(brand.getId()).thenReturn(3L);
        when(product.getBrandId()).thenReturn(3L);
        stubOptionStock(10L, null, 3);
        CartItem line = CartItem.forMember(1L, 10L, null, 2, null);
        when(cartItemRepository.findAllByMemberIdOrderByIdDesc(1L)).thenReturn(List.of(line));
        when(productRepository.findAllById(List.of(10L))).thenReturn(List.of(product));
        when(productOptionRepository.findAllById(List.of())).thenReturn(List.of());
        when(brandRepository.findAllById(List.of(3L))).thenReturn(List.of(brand));

        assertThat(cartService.getCart(1L, null).items())
                .singleElement()
                .extracting(com.jarvis.cart.dto.CartResponse.Item::maxQuantity)
                .isEqualTo(3);

        // 재고가 상한보다 넉넉하면 99에서 잘린다 — 재고 숫자를 그대로 내리면 FE가 스테퍼를 100까지 열어버린다
        stubOptionStock(10L, null, 500);
        assertThat(cartService.getCart(1L, null).items())
                .singleElement()
                .extracting(com.jarvis.cart.dto.CartResponse.Item::maxQuantity)
                .isEqualTo(CartItem.MAX_QUANTITY);
    }

    @Test
    @DisplayName("C-2 — 옵션 있는 상품에 optionId 누락은 CART_OPTION_REQUIRED, 남의 옵션은 CART_OPTION_INVALID")
    void optionValidation() {
        ProductOption option = mock(ProductOption.class);
        when(option.getId()).thenReturn(77L);
        when(productOptionRepository.findAllByProductIdOrderByIdAsc(10L)).thenReturn(List.of(option));
        // 되물음 목록은 구매 가능한 옵션만 담는다 (2026-08-09) — 재고가 있어야 이 경로를 탄다
        stubOptionStock(10L, 77L, 5);

        assertThatThrownBy(() -> cartService.addItem(1L, null, new CartAddRequest(10L, null, 1, null), null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CART_OPTION_REQUIRED);
        assertThatThrownBy(() -> cartService.addItem(1L, null, new CartAddRequest(10L, 999L, 1, null), null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CART_OPTION_INVALID);
    }

    @Test
    @DisplayName("C-2 — 동일 상품+옵션 재담기는 수량 합산, 합산 99 초과는 400")
    void addQuantityOverflowRejected() {
        when(productOptionRepository.findAllByProductIdOrderByIdAsc(10L)).thenReturn(List.of());
        CartItem existing = CartItem.forMember(1L, 10L, null, 98, null);
        ReflectionTestUtils.setField(existing, "id", 5L);
        when(cartItemRepository.findMemberLinesForUpdate(1L, 10L, null)).thenReturn(List.of(existing));

        CartService.CartAddResult ok = cartService.addItem(1L, null, new CartAddRequest(10L, null, 1, null), null);
        assertThat(ok.item().quantity()).isEqualTo(99);

        assertThatThrownBy(() -> cartService.addItem(1L, null, new CartAddRequest(10L, null, 1, null), null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("C-2 — 전 옵션 품절이면 CART_OPTION_REQUIRED가 아니라 CART_STOCK_INSUFFICIENT (2026-08-09)")
    void allOptionsSoldOutIsStockError() {
        ProductOption option = mock(ProductOption.class);
        when(option.getId()).thenReturn(77L);
        when(productOptionRepository.findAllByProductIdOrderByIdAsc(10L)).thenReturn(List.of(option));
        stubOptionStock(10L, 77L, 0);

        // 빈 목록으로 CART_OPTION_REQUIRED를 내면 LLM이 되물을 이름이 없어
        // "옵션을 선택해 주세요: 옵션." 같은 문구가 나간다(AI팀 실측)
        assertThatThrownBy(() -> cartService.addItem(1L, null, new CartAddRequest(10L, null, 1, null), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException ex = (BusinessException) e;
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CART_STOCK_INSUFFICIENT);
                    assertThat(ex.getDetail()).isEqualTo(java.util.Map.of("availableStock", 0));
                });
    }

    @Test
    @DisplayName("C-2 — 합산 후 수량이 재고 초과면 CART_STOCK_INSUFFICIENT + availableStock")
    void addStockInsufficient() {
        when(productStockRepository.findQuantity(10L, null)).thenReturn(Optional.of(3));
        when(productOptionRepository.findAllByProductIdOrderByIdAsc(10L)).thenReturn(List.of());
        when(cartItemRepository.findMemberLinesForUpdate(1L, 10L, null)).thenReturn(List.of());

        assertThatThrownBy(() -> cartService.addItem(1L, null, new CartAddRequest(10L, null, 5, null), null))
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
        when(productStockRepository.findQuantity(10L, null)).thenReturn(Optional.of(3));
        CartItem owned = CartItem.forMember(1L, 10L, null, 1, null);
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

        CartService.CartAddResult result = cartService.addItem(null, null, new CartAddRequest(10L, null, 2, null), null);

        assertThat(result.issuedGuestId()).isEqualTo("issued-guest-id");
        verify(guestService).ensureGuest(null);
    }

    @Test
    @DisplayName("병합 승계 — 동일 상품+옵션은 수량 합산 후 게스트 행 삭제, 없으면 소유자 변경 (02 D30)")
    void mergeGuestCart() {
        CartItem guestDup = CartItem.forGuest("g-1", 10L, null, 3, null);
        CartItem guestNew = CartItem.forGuest("g-1", 20L, null, 1, null);
        CartItem memberLine = CartItem.forMember(1L, 10L, null, 2, null);
        when(cartItemRepository.findAllByGuestId("g-1")).thenReturn(List.of(guestDup, guestNew));
        when(cartItemRepository.findMemberLinesForUpdate(1L, 10L, null)).thenReturn(List.of(memberLine));
        when(cartItemRepository.findMemberLinesForUpdate(1L, 20L, null)).thenReturn(List.of());

        cartService.mergeGuestCart(1L, "g-1");

        assertThat(memberLine.getQuantity()).isEqualTo(5);
        verify(cartItemRepository).delete(guestDup);
        assertThat(guestNew.getMemberId()).isEqualTo(1L);
        assertThat(guestNew.getGuestId()).isNull();
    }

    // ---- 행동 이벤트 서버 적재 (노션 E-1·C-2·C-4 2026-08-06 이관) ----

    @Test
    @DisplayName("C-2 — quantity는 이번 요청분(delta), price는 옵션 추가금 포함")
    void recordsAddEventWithDeltaAndOptionPrice() {
        ProductOption option = mock(ProductOption.class, withSettings().strictness(Strictness.LENIENT));
        when(option.getId()).thenReturn(77L);
        when(option.getExtraPrice()).thenReturn(2000);
        when(product.getPrice()).thenReturn(12000);
        when(productOptionRepository.findAllByProductIdOrderByIdAsc(10L)).thenReturn(List.of(option));
        when(productOptionRepository.findById(77L)).thenReturn(Optional.of(option));
        // 이미 3개 담겨 있는데 2개 더 담는다 — 합산은 5지만 이벤트는 2여야 한다
        CartItem existing = CartItem.forMember(1L, 10L, 77L, 3, null);
        ReflectionTestUtils.setField(existing, "id", 5L);
        when(cartItemRepository.findMemberLinesForUpdate(1L, 10L, 77L)).thenReturn(List.of(existing));

        cartService.addItem(1L, null, new CartAddRequest(10L, 77L, 2, null), "sess-1");

        ArgumentCaptor<CartEventRecorder.CartEvent> captor =
                ArgumentCaptor.forClass(CartEventRecorder.CartEvent.class);
        verify(cartEventRecorder).record(captor.capture());
        CartEventRecorder.CartEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo(CartEventRecorder.ADD_EVENT_TYPE);
        assertThat(event.quantity()).isEqualTo(2);          // 합산 결과 5가 아니다
        assertThat(event.price()).isEqualTo(14000);         // 12000 + 옵션 2000
        assertThat(event.optionId()).isEqualTo(77L);
        assertThat(event.sessionKey()).isEqualTo("sess-1");
    }

    // 커밋 뒤엔 행이 없어 quantity·optionId를 알 수 없다 — 지우기 전에 뽑아야 한다
    @Test
    @DisplayName("C-4 — 삭제 이벤트는 지우기 전 값으로 만들고 quantity는 전량이다")
    void recordsRemoveEventWithPreDeletionValues() {
        when(product.getPrice()).thenReturn(12000);
        CartItem owned = CartItem.forMember(1L, 10L, null, 4, null);
        ReflectionTestUtils.setField(owned, "id", 5L);
        when(cartItemRepository.findById(5L)).thenReturn(Optional.of(owned));

        cartService.removeItem(1L, null, 5L, "sess-1");

        ArgumentCaptor<CartEventRecorder.CartEvent> captor =
                ArgumentCaptor.forClass(CartEventRecorder.CartEvent.class);
        verify(cartEventRecorder).record(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(CartEventRecorder.REMOVE_EVENT_TYPE);
        assertThat(captor.getValue().quantity()).isEqualTo(4);   // 부분 차감 없음 — 전량
        verify(cartItemRepository).delete(owned);
    }

    // 404는 미적재 — 삭제 버튼 연타의 두 번째까지 세면 이벤트가 부풀려진다 (노션 C-4)
    @Test
    @DisplayName("C-4 — 없는 항목(404)은 이벤트를 적재하지 않는다")
    void doesNotRecordRemoveEventOnNotFound() {
        when(cartItemRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.removeItem(1L, null, 5L, "sess-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CART_ITEM_NOT_FOUND);
        verifyNoInteractions(cartEventRecorder);
    }

    // ---- 추천 귀속 (노션 C-2·I-2) ----

    private static final String LIST_ID = "9f2c1a7e4b8d43f5a0c6e1d97b3f8a24";
    private static final String REQUEST_ID = "a63be350-ec96-4f44-b3f9-c962b6673a68";

    private static final RecommendationContext CONTEXT = new RecommendationContext(REQUEST_ID, LIST_ID);
    private static final ConversionAttribution VERIFIED =
            new ConversionAttribution(REQUEST_ID, LIST_ID);

    @Test
    @DisplayName("C-2 — 추천 카드에서 담으면 검증된 출처가 cart_item에 저장된다")
    void addStoresVerifiedAttribution() {
        when(productOptionRepository.findAllByProductIdOrderByIdAsc(10L)).thenReturn(List.of());
        when(cartItemRepository.findMemberLinesForUpdate(1L, 10L, null)).thenReturn(List.of());
        when(attributionResolver.resolveForConversion(CONTEXT, 10L, 1L, null)).thenReturn(VERIFIED);

        cartService.addItem(1L, null, new CartAddRequest(10L, null, 1, CONTEXT), null);

        verify(cartItemRepository).save(argThat(item ->
                LIST_ID.equals(item.getListId()) && REQUEST_ID.equals(item.getRecommendationRequestId())));
    }

    // 분석 데이터가 이상하다고 사용자의 담기를 막으면 안 된다 (노션 C-2 「검증 규칙」)
    @Test
    @DisplayName("C-2 — 출처 검증에 실패해도 담기는 성공하고 출처만 비워진다")
    void addSucceedsWhenAttributionRejected() {
        when(productOptionRepository.findAllByProductIdOrderByIdAsc(10L)).thenReturn(List.of());
        when(cartItemRepository.findMemberLinesForUpdate(1L, 10L, null)).thenReturn(List.of());
        when(attributionResolver.resolveForConversion(CONTEXT, 10L, 1L, null))
                .thenReturn(ConversionAttribution.NONE);

        CartService.CartAddResult result =
                cartService.addItem(1L, null, new CartAddRequest(10L, null, 1, CONTEXT), null);

        assertThat(result.item().quantity()).isEqualTo(1);
        verify(cartItemRepository).save(argThat(item -> item.getListId() == null));
    }

    @Test
    @DisplayName("C-2 재담기 — 검증된 새 출처만 덮어쓰고(last_valid_touch), 출처 없는 재담기는 기존 값을 지우지 않는다")
    void reAddOverwritesOnlyWithValidAttribution() {
        when(productOptionRepository.findAllByProductIdOrderByIdAsc(10L)).thenReturn(List.of());
        CartItem existing = CartItem.forMember(1L, 10L, null, 1, null);
        ReflectionTestUtils.setField(existing, "id", 5L);
        when(cartItemRepository.findMemberLinesForUpdate(1L, 10L, null)).thenReturn(List.of(existing));
        when(attributionResolver.resolveForConversion(CONTEXT, 10L, 1L, null)).thenReturn(VERIFIED);

        cartService.addItem(1L, null, new CartAddRequest(10L, null, 1, CONTEXT), null);
        assertThat(existing.getListId()).isEqualTo(LIST_ID);

        cartService.addItem(1L, null, new CartAddRequest(10L, null, 1, null), null);
        assertThat(existing.getListId()).isEqualTo(LIST_ID);
    }

    @Test
    @DisplayName("병합 승계 — 사라지는 게스트 라인의 출처는 회원 라인이 비어 있을 때만 물려받는다")
    void mergeInheritsAttributionOnlyWhenEmpty() {
        CartItem guestWithSource = CartItem.forGuest("g-1", 10L, null, 1, VERIFIED);
        CartItem guestOther = CartItem.forGuest("g-1", 20L, null, 1, VERIFIED);
        CartItem emptyMemberLine = CartItem.forMember(1L, 10L, null, 1, null);
        CartItem ownedMemberLine = CartItem.forMember(1L, 20L, null, 1,
                new ConversionAttribution("other-request", "other-list"));
        when(cartItemRepository.findAllByGuestId("g-1"))
                .thenReturn(List.of(guestWithSource, guestOther));
        when(cartItemRepository.findMemberLinesForUpdate(1L, 10L, null))
                .thenReturn(List.of(emptyMemberLine));
        when(cartItemRepository.findMemberLinesForUpdate(1L, 20L, null))
                .thenReturn(List.of(ownedMemberLine));

        cartService.mergeGuestCart(1L, "g-1");

        assertThat(emptyMemberLine.getListId()).isEqualTo(LIST_ID);
        assertThat(ownedMemberLine.getListId()).isEqualTo("other-list");
    }

    @Test
    @DisplayName("C-3/C-4 — 남의 항목 접근은 403 AUTH_FORBIDDEN")
    void ownershipGuard() {
        CartItem foreign = CartItem.forMember(2L, 10L, null, 1, null);
        when(cartItemRepository.findById(5L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> cartService.changeQuantity(1L, null, 5L, 3))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_FORBIDDEN);
    }

    // ---- 주문(O-1·O-2)이 경유하는 진입점 ----

    @Test
    @DisplayName("O-1 — 남의 장바구니 행이 섞이면 CART_ITEM_NOT_FOUND")
    void getOwnedLinesRejectsForeignLines() {
        CartItem foreign = CartItem.forMember(2L, 10L, null, 1, null);
        when(cartItemRepository.findAllById(List.of(5L))).thenReturn(List.of(foreign));

        assertThatThrownBy(() -> cartService.getOwnedLines(1L, List.of(5L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CART_ITEM_NOT_FOUND);
    }

    @Test
    @DisplayName("O-1 — 없는 id가 섞여 조회 결과가 모자라도 CART_ITEM_NOT_FOUND")
    void getOwnedLinesRejectsMissingIds() {
        CartItem mine = CartItem.forMember(1L, 10L, null, 1, null);
        when(cartItemRepository.findAllById(List.of(5L, 6L))).thenReturn(List.of(mine));

        assertThatThrownBy(() -> cartService.getOwnedLines(1L, List.of(5L, 6L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CART_ITEM_NOT_FOUND);
    }

    @Test
    @DisplayName("O-2 — 주문한 (상품, 옵션명)과 같은 행만 지운다. 옵션이 다르면 남긴다")
    void removeLinesMatchingComparesOptionName() {
        CartItem sameOption = CartItem.forMember(1L, 10L, 100L, 1, null);
        CartItem otherOption = CartItem.forMember(1L, 10L, 200L, 1, null);
        CartItem otherProduct = CartItem.forMember(1L, 20L, null, 1, null);
        when(cartItemRepository.findAllByMemberId(1L))
                .thenReturn(List.of(sameOption, otherOption, otherProduct));
        ProductOption large = option(100L, "L");
        ProductOption small = option(200L, "S");
        when(productOptionRepository.findAllById(List.of(100L, 200L)))
                .thenReturn(List.of(large, small));

        cartService.removeLinesMatching(1L,
                List.of(new CartService.PurchasedLine(10L, "L")));

        verify(cartItemRepository).deleteAll(List.of(sameOption));
    }

    @Test
    @DisplayName("O-2 — 장바구니가 비어 있으면 옵션 조회조차 하지 않는다")
    void removeLinesMatchingSkipsEmptyCart() {
        when(cartItemRepository.findAllByMemberId(1L)).thenReturn(List.of());

        cartService.removeLinesMatching(1L,
                List.of(new CartService.PurchasedLine(10L, null)));

        verifyNoInteractions(productOptionRepository);
    }

    private ProductOption option(Long id, String name) {
        ProductOption option = mock(ProductOption.class, withSettings().strictness(Strictness.LENIENT));
        when(option.getId()).thenReturn(id);
        when(option.getName()).thenReturn(name);
        return option;
    }
    /** C-1은 줄마다 담은 옵션의 재고를 본다 (02 D33 개정) — findAllByProductIdIn 경로를 태운다 */
    private void stubOptionStock(Long productId, Long optionId, int quantity) {
        when(productStockRepository.findAllByProductIdIn(java.util.Set.of(productId)))
                .thenReturn(List.of(com.jarvis.product.ProductStock.of(productId, optionId, quantity)));
    }

}
