package com.jarvis.cart;

import com.jarvis.cart.dto.CartAddRequest;
import com.jarvis.cart.dto.CartItemResponse;
import com.jarvis.cart.dto.CartQuantityRequest;
import com.jarvis.cart.dto.CartResponse;
import com.jarvis.global.auth.AuthUser;
import com.jarvis.global.auth.GuestCookieManager;
import com.jarvis.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-1~C-4 (04 §3) — 게스트 허용(permitAll). 신원은 JWT 우선, 없으면 guest_id 쿠키.
 * 담기 성공 시 행동 이벤트(add_to_cart)는 FE가 E-1로 전송 — 서버 적재 없음 (02 D31).
 */
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;
    private final GuestCookieManager guestCookieManager;

    @GetMapping
    public ApiResponse<CartResponse> getCart(@AuthenticationPrincipal AuthUser authUser,
                                             HttpServletRequest httpRequest) {
        return ApiResponse.success(cartService.getCart(
                memberId(authUser), guestId(httpRequest)));
    }

    @PostMapping("/items")
    public ApiResponse<CartItemResponse> addItem(@Valid @RequestBody CartAddRequest request,
                                                 @AuthenticationPrincipal AuthUser authUser,
                                                 HttpServletRequest httpRequest,
                                                 HttpServletResponse httpResponse) {
        CartService.CartAddResult result = cartService.addItem(
                memberId(authUser), guestId(httpRequest), request);
        if (result.issuedGuestId() != null) {
            guestCookieManager.write(httpResponse, result.issuedGuestId());
        }
        return ApiResponse.success(result.item());
    }

    @PatchMapping("/items/{id}")
    public ApiResponse<CartItemResponse> changeQuantity(@PathVariable Long id,
                                                        @Valid @RequestBody CartQuantityRequest request,
                                                        @AuthenticationPrincipal AuthUser authUser,
                                                        HttpServletRequest httpRequest) {
        return ApiResponse.success(cartService.changeQuantity(
                memberId(authUser), guestId(httpRequest), id, request.quantity()));
    }

    @DeleteMapping("/items/{id}")
    public ApiResponse<Void> removeItem(@PathVariable Long id,
                                        @AuthenticationPrincipal AuthUser authUser,
                                        HttpServletRequest httpRequest) {
        cartService.removeItem(memberId(authUser), guestId(httpRequest), id);
        return ApiResponse.success(null);
    }

    private Long memberId(AuthUser authUser) {
        return authUser == null ? null : authUser.memberId();
    }

    private String guestId(HttpServletRequest httpRequest) {
        return guestCookieManager.resolve(httpRequest).orElse(null);
    }
}
