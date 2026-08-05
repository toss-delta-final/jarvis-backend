package com.jarvis.internal;

import com.jarvis.cart.CartService;
import com.jarvis.cart.dto.CartAddRequest;
import com.jarvis.cart.dto.CartItemResponse;
import com.jarvis.cart.dto.CartQuantityRequest;
import com.jarvis.global.response.ApiResponse;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.internal.dto.InternalCartAddRequest;
import com.jarvis.internal.dto.InternalCartResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * I-2·I-18·I-24·I-25 (04 §10) — ②유저 직접 담기와 같은 CartService 재사용 (03 §7 ④ "입구는 둘, 로직은 하나").
 * userId/guestId는 채팅 티켓의 메아리 — 게스트도 담기 성공 (02 D30).
 */
@RestController
@RequestMapping("/internal/cart")
@RequiredArgsConstructor
public class InternalCartController {

    private final CartService cartService;

    /** I-2 — 옵션 필요 시 400 CART_OPTION_REQUIRED + error.detail.options (05 §I-2) */
    @PostMapping("/items")
    public ApiResponse<CartItemResponse> addItem(@Valid @RequestBody InternalCartAddRequest request) {
        CartService.CartAddResult result = cartService.addItem(request.userId(), request.guestId(),
                new CartAddRequest(request.productId(), request.optionId(), request.quantity()));
        return ApiResponse.success(result.item());
    }

    /** I-18 — 응답 item에 productName·optionName 포함(LLM이 그대로 발화), 빈 장바구니도 200 (05 §I-18) */
    @GetMapping
    public ApiResponse<InternalCartResponse> getCart(@RequestParam(required = false) Long userId,
                                                     @RequestParam(required = false) String guestId) {
        requireExactlyOneIdentity(userId, guestId);
        return ApiResponse.success(InternalCartResponse.from(cartService.getCart(userId, guestId)));
    }

    /** I-25 — 합산이 아니라 치환이라 보낸 값 자체를 재고와 비교. 합산은 I-2 재호출 (05 §I-25) */
    @PatchMapping("/items/{cartItemId}")
    public ApiResponse<CartItemResponse> changeQuantity(@PathVariable Long cartItemId,
                                                        @Valid @RequestBody CartQuantityRequest request,
                                                        @RequestParam(required = false) Long userId,
                                                        @RequestParam(required = false) String guestId) {
        requireExactlyOneIdentity(userId, guestId);
        return ApiResponse.success(
                cartService.changeQuantity(userId, guestId, cartItemId, request.quantity()));
    }

    /** I-24 — 복수 삭제는 항목별 반복 호출(C-4와 같이 bulk 없음), 두 번째 삭제는 404 (05 §I-24) */
    @DeleteMapping("/items/{cartItemId}")
    public ApiResponse<Void> removeItem(@PathVariable Long cartItemId,
                                        @RequestParam(required = false) Long userId,
                                        @RequestParam(required = false) String guestId) {
        requireExactlyOneIdentity(userId, guestId);
        cartService.removeItem(userId, guestId, cartItemId);
        return ApiResponse.success(null);
    }

    /**
     * userId XOR guestId — 둘 다 없거나 둘 다 있으면 400 (05 §I-18).
     * 신원은 AI가 검증한 티켓 sub의 메아리라 요청이 둘을 동시에 주장할 수 없다.
     */
    private void requireExactlyOneIdentity(Long userId, String guestId) {
        if ((userId == null) == (guestId == null)) {
            throw new BusinessException(ErrorCode.CART_QUERY_INVALID);
        }
    }
}
