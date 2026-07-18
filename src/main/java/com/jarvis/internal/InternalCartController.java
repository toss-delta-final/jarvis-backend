package com.jarvis.internal;

import com.jarvis.cart.CartService;
import com.jarvis.cart.dto.CartAddRequest;
import com.jarvis.cart.dto.CartItemResponse;
import com.jarvis.cart.dto.CartResponse;
import com.jarvis.global.response.ApiResponse;
import com.jarvis.internal.dto.InternalCartAddRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * I-2·I-18 (04 §10) — ②유저 직접 담기와 같은 CartService 재사용 (03 §7 ④ "입구는 둘, 로직은 하나").
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
    public ApiResponse<CartResponse> getCart(@RequestParam(required = false) Long userId,
                                             @RequestParam(required = false) String guestId) {
        return ApiResponse.success(cartService.getCart(userId, guestId));
    }
}
