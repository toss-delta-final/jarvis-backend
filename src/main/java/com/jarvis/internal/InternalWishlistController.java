package com.jarvis.internal;

import com.jarvis.global.response.ApiResponse;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.internal.dto.InternalWishlistAddRequest;
import com.jarvis.product.dto.ProductCardListResponse;
import com.jarvis.wishlist.WishlistService;
import com.jarvis.wishlist.dto.WishlistAddResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * I-26·I-27·I-28 (04 §10) — M-4~6과 같은 WishlistService 재사용 (03 §7 ④ "입구는 둘, 로직은 하나").
 * userId는 채팅 티켓의 메아리이고 역할 검사는 없다 (05 §0-1 — 찜은 위험 3축 어디에도 걸리지 않는다).
 * 게스트 찜은 존재하지 않으므로(M-4) guestId를 받지 않는다 — 로그인 안내는 AI 몫.
 */
@RestController
@RequestMapping("/internal/wishlist")
@RequiredArgsConstructor
public class InternalWishlistController {

    private final WishlistService wishlistService;

    /** I-28 — I-27 지칭 해소용. 찜이 없어도 200 + 빈 배열, HIDDEN·품절도 purchaseState로 남는다 (05 §I-28) */
    @GetMapping
    public ApiResponse<ProductCardListResponse> getList(@RequestParam(required = false) Long userId) {
        requireUserId(userId);
        return ApiResponse.success(new ProductCardListResponse(wishlistService.getList(userId)));
    }

    /** I-26 — 중복은 409 WISHLIST_DUPLICATE(멱등 200으로 뭉개지 않음), HIDDEN·품절도 찜 가능 (05 §I-26) */
    @PostMapping
    public ApiResponse<WishlistAddResponse> add(@Valid @RequestBody InternalWishlistAddRequest request) {
        wishlistService.add(request.userId(), request.productId());
        return ApiResponse.success(new WishlistAddResponse(request.productId()));
    }

    /** I-27 — 키가 (userId, productId)라 없는 상품과 안 찜한 상품이 모두 404 (05 §I-27) */
    @DeleteMapping("/{productId}")
    public ApiResponse<Void> remove(@PathVariable Long productId,
                                    @RequestParam(required = false) Long userId) {
        requireUserId(userId);
        wishlistService.remove(userId, productId);
        return ApiResponse.success(null);
    }

    /**
     * 신원은 AI가 검증한 티켓 sub의 메아리라 누락은 계약 위반이다 — I-18의 자원별 query code 전례를 따른다.
     * body 신원(I-26)은 @Valid가 VALIDATION_ERROR + fields로 거르므로 여기서 다루지 않는다(I-2와 동일).
     */
    private void requireUserId(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.WISHLIST_QUERY_INVALID);
        }
    }
}
