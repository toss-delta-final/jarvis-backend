package com.jarvis.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jarvis.global.response.ApiResponse;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.internal.dto.InternalWishlistAddRequest;
import com.jarvis.product.dto.ProductCardListResponse;
import com.jarvis.product.dto.ProductCardResponse;
import com.jarvis.wishlist.WishlistService;
import com.jarvis.wishlist.dto.WishlistAddResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * I-26·I-27·I-28 — 컨트롤러가 지는 유일한 판단은 query 신원 누락 검증이다.
 * 중복·미존재 상품·찜 없음은 WishlistService(M-4~6과 같은 코드) 소관이라 WishlistServiceTest가 덮는다.
 */
@ExtendWith(MockitoExtension.class)
class InternalWishlistControllerTest {

    @Mock WishlistService wishlistService;

    @InjectMocks InternalWishlistController controller;

    @Test
    @DisplayName("I-28 — 서비스가 준 목록을 변환 없이 data.items로 감싼다 (M-4와 같은 모양)")
    void getListWrapsCards() {
        // 카드 필드는 M-4와 공유하는 ProductCardResponse 소관이라 여기서 모양을 고정하지 않는다
        List<ProductCardResponse> cards = new ArrayList<>();
        when(wishlistService.getList(123L)).thenReturn(cards);

        ApiResponse<ProductCardListResponse> response = controller.getList(123L);

        assertThat(response.success()).isTrue();
        assertThat(response.data().items()).isSameAs(cards);
    }

    @Test
    @DisplayName("I-28 — 찜이 없어도 404가 아니라 200 + 빈 배열")
    void getListReturnsEmptyList() {
        when(wishlistService.getList(123L)).thenReturn(List.of());

        assertThat(controller.getList(123L).data().items()).isEmpty();
    }

    @Test
    @DisplayName("I-26 — 성공 응답은 productId (별도 wishlistId 없음 — 해제 키와 정합)")
    void addReturnsProductId() {
        ApiResponse<WishlistAddResponse> response =
                controller.add(new InternalWishlistAddRequest(123L, 1L));

        assertThat(response.data()).isEqualTo(new WishlistAddResponse(1L));
        verify(wishlistService).add(123L, 1L);
    }

    @Test
    @DisplayName("I-27 — 해제 성공은 data null, 키는 wishlistId가 아니라 productId")
    void removeReturnsNullData() {
        ApiResponse<Void> response = controller.remove(1L, 123L);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNull();
        verify(wishlistService).remove(123L, 1L);
    }

    @Test
    @DisplayName("I-28 — userId 누락은 WISHLIST_QUERY_INVALID (서비스 호출 없음)")
    void getListRejectsMissingUserId() {
        assertThatThrownBy(() -> controller.getList(null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.WISHLIST_QUERY_INVALID);
        verifyNoInteractions(wishlistService);
    }

    @Test
    @DisplayName("I-27 — userId 누락은 WISHLIST_QUERY_INVALID (서비스 호출 없음)")
    void removeRejectsMissingUserId() {
        assertThatThrownBy(() -> controller.remove(1L, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.WISHLIST_QUERY_INVALID);
        verifyNoInteractions(wishlistService);
    }
}
