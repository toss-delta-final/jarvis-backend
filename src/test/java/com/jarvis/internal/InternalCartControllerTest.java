package com.jarvis.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jarvis.cart.CartService;
import com.jarvis.cart.dto.CartItemResponse;
import com.jarvis.cart.dto.CartQuantityRequest;
import com.jarvis.global.response.ApiResponse;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.internal.dto.InternalCartItemResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * I-24·I-25 — 컨트롤러가 지는 유일한 판단은 신원 XOR 검증이다.
 * 소유권·재고·not-found는 CartService(C-3·C-4와 같은 코드) 소관이라 CartServiceTest가 덮는다.
 */
@ExtendWith(MockitoExtension.class)
class InternalCartControllerTest {

    @Mock CartService cartService;

    @InjectMocks InternalCartController controller;

    @Test
    @DisplayName("I-25 — 회원 신원으로 수량 치환을 CartService에 위임한다")
    void changeQuantityDelegatesForMember() {
        when(cartService.changeQuantity(123L, null, 55L, 3))
                .thenReturn(new CartItemResponse(55L, 3));

        ApiResponse<InternalCartItemResponse> response =
                controller.changeQuantity(55L, new CartQuantityRequest(3), 123L, null);

        assertThat(response.success()).isTrue();
        // internal은 id를 숫자로 유지한다 — 공개 CartItemResponse의 @StringId가 새어 나가면 안 된다
        assertThat(response.data()).isEqualTo(new InternalCartItemResponse(55L, 3));
    }

    @Test
    @DisplayName("I-25 — 게스트도 수량을 바꿀 수 있다 (02 D30)")
    void changeQuantityDelegatesForGuest() {
        String guestId = "550e8400-e29b-41d4-a716-446655440000";
        when(cartService.changeQuantity(null, guestId, 55L, 2))
                .thenReturn(new CartItemResponse(55L, 2));

        ApiResponse<InternalCartItemResponse> response =
                controller.changeQuantity(55L, new CartQuantityRequest(2), null, guestId);

        assertThat(response.data().quantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("I-24 — 삭제 성공은 data null (C-4와 동일 — cartItemId를 싣지 않는다)")
    void removeItemReturnsNullData() {
        ApiResponse<Void> response = controller.removeItem(55L, 123L, null, "chat-abc");

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNull();
        // chatSessionId는 "chat:" sentinel로 조립돼 넘어간다 (노션 I-24 C안)
        verify(cartService).removeItem(123L, null, 55L, "chat:chat-abc");
    }

    @Test
    @DisplayName("I-24 — 신원이 둘 다 없으면 VALIDATION_ERROR (I-18과 code가 갈린다)")
    void removeItemRejectsMissingIdentity() {
        assertThatThrownBy(() -> controller.removeItem(55L, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        verifyNoInteractions(cartService);
    }

    @Test
    @DisplayName("I-25 — 신원을 둘 다 주장하면 VALIDATION_ERROR (I-18과 code가 갈린다)")
    void changeQuantityRejectsAmbiguousIdentity() {
        assertThatThrownBy(() ->
                controller.changeQuantity(55L, new CartQuantityRequest(3), 123L, "guest-uuid"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        verifyNoInteractions(cartService);
    }
}
