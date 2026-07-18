package com.jarvis.wishlist;

import com.jarvis.global.auth.AuthUser;
import com.jarvis.global.response.ApiResponse;
import com.jarvis.product.dto.ProductCardResponse;
import com.jarvis.wishlist.dto.WishlistAddRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** M-4~6 (04 §5) — /api/wishlist/**는 USER 가드 (SecurityConfig) */
@RestController
@RequestMapping("/api/wishlist")
@RequiredArgsConstructor
public class WishlistController {

    private final WishlistService wishlistService;

    @GetMapping
    public ApiResponse<List<ProductCardResponse>> getList(@AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.success(wishlistService.getList(authUser.memberId()));
    }

    @PostMapping
    public ApiResponse<Void> add(@Valid @RequestBody WishlistAddRequest request,
                                 @AuthenticationPrincipal AuthUser authUser) {
        wishlistService.add(authUser.memberId(), request.productId());
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{productId}")
    public ApiResponse<Void> remove(@PathVariable Long productId,
                                    @AuthenticationPrincipal AuthUser authUser) {
        wishlistService.remove(authUser.memberId(), productId);
        return ApiResponse.success(null);
    }
}
