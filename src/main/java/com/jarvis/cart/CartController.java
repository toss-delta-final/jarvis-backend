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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-1~C-4 (04 §3) — 게스트 허용(permitAll). 신원은 JWT 우선, 없으면 guest_id 쿠키.
 *
 * <p><b>2026-08-06 개정</b> — 담기·삭제 행동 이벤트를 <b>서버가 적재</b>한다(구: FE가 E-1로 전송,
 * 02 D31). {@code X-Session-Key}는 FE가 요청 시점의 localStorage 값을 싣는 <b>분석용</b> 헤더이며
 * 신원이 아니다 — 신원은 JWT·{@code guest_id} 쿠키다. 누락돼도 담기·삭제는 정상 처리하고
 * 이벤트만 건너뛴다.
 */
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    /** 분석용 세션 키 헤더 (노션 C-2·C-4 2026-08-06 신설) — CORS 허용 헤더는 `*`라 별도 설정 불필요 */
    private static final String SESSION_KEY_HEADER = "X-Session-Key";

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
                                                 @RequestHeader(value = SESSION_KEY_HEADER, required = false)
                                                 String sessionKey,
                                                 HttpServletRequest httpRequest,
                                                 HttpServletResponse httpResponse) {
        CartService.CartAddResult result = cartService.addItem(
                memberId(authUser), guestId(httpRequest), request, sessionKey);
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
                                        @RequestHeader(value = SESSION_KEY_HEADER, required = false)
                                        String sessionKey,
                                        HttpServletRequest httpRequest) {
        cartService.removeItem(memberId(authUser), guestId(httpRequest), id, sessionKey);
        return ApiResponse.success(null);
    }

    private Long memberId(AuthUser authUser) {
        return authUser == null ? null : authUser.memberId();
    }

    private String guestId(HttpServletRequest httpRequest) {
        return guestCookieManager.resolve(httpRequest).orElse(null);
    }
}
