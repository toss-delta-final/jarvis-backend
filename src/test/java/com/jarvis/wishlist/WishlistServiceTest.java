package com.jarvis.wishlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.product.Product;
import com.jarvis.product.ProductRepository;
import com.jarvis.product.ProductService;
import com.jarvis.product.dto.ProductCardResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** M-4~M-6 찜 (04 §5) */
@ExtendWith(MockitoExtension.class)
class WishlistServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long PRODUCT_ID = 10L;

    @Mock WishlistRepository wishlistRepository;
    @Mock ProductRepository productRepository;
    @Mock ProductService productService;

    @InjectMocks WishlistService wishlistService;

    @Test
    @DisplayName("M-5 — 찜 추가 성공")
    void addSaved() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(mock(Product.class)));
        when(wishlistRepository.existsByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID)).thenReturn(false);

        wishlistService.add(MEMBER_ID, PRODUCT_ID);

        verify(wishlistRepository).save(any(Wishlist.class));
    }

    @Test
    @DisplayName("M-5 — 중복 찜은 409 WISHLIST_DUPLICATE")
    void addDuplicate() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(mock(Product.class)));
        when(wishlistRepository.existsByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID)).thenReturn(true);

        assertThatThrownBy(() -> wishlistService.add(MEMBER_ID, PRODUCT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.WISHLIST_DUPLICATE);
    }

    @Test
    @DisplayName("M-5 — 없는 상품 찜은 404 PRODUCT_NOT_FOUND")
    void addMissingProduct() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> wishlistService.add(MEMBER_ID, PRODUCT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("M-6 — 찜하지 않은 상품 해제는 404 WISHLIST_NOT_FOUND")
    void removeMissing() {
        when(wishlistRepository.findByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> wishlistService.remove(MEMBER_ID, PRODUCT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.WISHLIST_NOT_FOUND);
    }

    @Test
    @DisplayName("M-6 — 찜 해제 성공")
    void removeDeleted() {
        Wishlist wishlist = mock(Wishlist.class);
        when(wishlistRepository.findByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID))
                .thenReturn(Optional.of(wishlist));

        wishlistService.remove(MEMBER_ID, PRODUCT_ID);

        verify(wishlistRepository).delete(wishlist);
    }

    @Test
    @DisplayName("M-4 — 목록은 최근 찜 순서의 productId로 카드 조립")
    void listAssemblesCardsInOrder() {
        Wishlist w1 = mock(Wishlist.class);
        Wishlist w2 = mock(Wishlist.class);
        when(w1.getProductId()).thenReturn(30L);
        when(w2.getProductId()).thenReturn(10L);
        when(wishlistRepository.findAllByMemberIdOrderByIdDesc(MEMBER_ID)).thenReturn(List.of(w1, w2));
        List<ProductCardResponse> cards = List.of(
                mock(ProductCardResponse.class), mock(ProductCardResponse.class));
        when(productService.getCardsByIds(List.of(30L, 10L))).thenReturn(cards);

        assertThat(wishlistService.getList(MEMBER_ID)).isSameAs(cards);
    }
}
