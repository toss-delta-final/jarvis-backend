package com.jarvis.product;

import com.jarvis.global.auth.AuthUser;
import com.jarvis.global.response.ApiResponse;
import com.jarvis.product.dto.PopularCardListResponse;
import com.jarvis.product.dto.ProductCardListResponse;
import com.jarvis.product.dto.ProductDetailResponse;
import com.jarvis.product.dto.RecommendedProductsResponse;
import com.jarvis.recommendation.HomeRecommendationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** P-2·P-4·P-5·M-7 (04 §2) */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
@Validated
public class ProductController {

    /** M-7 — 중복 제거 최신 20개 고정 (04 §5) */
    private static final int RECENT_SIZE = 20;

    private final ProductService productService;
    private final HomeRecommendationService homeRecommendationService;

    /** popular가 {id} 경로보다 먼저 매칭되도록 정적 경로 우선 — 스프링이 자동 처리 */
    @GetMapping("/popular")
    public ApiResponse<PopularCardListResponse> popular(
            @RequestParam(defaultValue = "12") @Min(1) @Max(50) int size) {
        return ApiResponse.success(new PopularCardListResponse(productService.getPopular(size)));
    }

    /**
     * P-5 — 🔑 USER 전용(게스트는 P-4를 직접 호출). <b>항상 200</b>이다 — I-22 타임아웃·에러·프로필
     * 없음은 전부 인기상품 대체로 내려가고, 5xx는 Spring 자체 장애일 때만 난다(노션 P-5).
     * 파라미터는 없다 — 사용자는 AT에서 식별한다.
     */
    @GetMapping("/recommended")
    public ApiResponse<RecommendedProductsResponse> recommended(
            @AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.success(homeRecommendationService.recommend(authUser.memberId()));
    }

    /** M-7 — 🔑 USER 가드 (SecurityConfig의 /api/products/recent 선행 매칭) */
    @GetMapping("/recent")
    public ApiResponse<ProductCardListResponse> recent(@AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.success(
                new ProductCardListResponse(productService.getRecent(authUser.memberId(), RECENT_SIZE)));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductDetailResponse> detail(@PathVariable Long id) {
        return ApiResponse.success(productService.getDetail(id));
    }
}
