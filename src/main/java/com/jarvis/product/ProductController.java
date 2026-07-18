package com.jarvis.product;

import com.jarvis.global.auth.AuthUser;
import com.jarvis.global.response.ApiResponse;
import com.jarvis.product.dto.ProductCardResponse;
import com.jarvis.product.dto.ProductDetailResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** P-2·P-4 (04 §2). P-5(추천)는 Phase 5, P-7(카드 하이드레이션)은 Phase 5 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
@Validated
public class ProductController {

    /** M-7 — 중복 제거 최신 20개 고정 (04 §5) */
    private static final int RECENT_SIZE = 20;

    private final ProductService productService;

    /** popular가 {id} 경로보다 먼저 매칭되도록 정적 경로 우선 — 스프링이 자동 처리 */
    @GetMapping("/popular")
    public ApiResponse<List<ProductCardResponse>> popular(
            @RequestParam(defaultValue = "12") @Min(1) @Max(50) int size) {
        return ApiResponse.success(productService.getPopular(size));
    }

    /**
     * P-7 — 추천 카드 하이드레이션 (04 §2, CH-5 확정 시 폐지 예고 + 범용 다건 카드 조회로 유지).
     * ids 상한 20, HIDDEN·품절 드롭. 표시 데이터 전용 — 결제 금액의 진실은 O-1 재계산.
     */
    @GetMapping("/cards")
    public ApiResponse<List<ProductCardResponse>> cards(@RequestParam List<Long> ids) {
        return ApiResponse.success(productService.getPublicCards(ids));
    }

    /** M-7 — 🔑 USER 가드 (SecurityConfig의 /api/products/recent 선행 매칭) */
    @GetMapping("/recent")
    public ApiResponse<List<ProductCardResponse>> recent(@AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.success(productService.getRecent(authUser.memberId(), RECENT_SIZE));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductDetailResponse> detail(@PathVariable Long id) {
        return ApiResponse.success(productService.getDetail(id));
    }
}
