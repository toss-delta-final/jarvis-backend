package com.jarvis.seller;

import com.jarvis.brand.Brand;
import com.jarvis.global.auth.AuthUser;
import com.jarvis.global.response.ApiResponse;
import com.jarvis.seller.dto.SellerOrderListResponse;
import com.jarvis.seller.dto.SellerProductListResponse;
import com.jarvis.seller.dto.SellerSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-1/S-2/S-3 (노션 seller 화면 확정본 반영) — /api/seller/**는 SecurityConfig에서 ROLE_SELLER 가드.
 * brandId는 항상 JWT의 memberId → brand.seller_id 도출(클라이언트 주장 무시). 조회 전용(상품 수정은 챗봇 I-11만).
 * 파라미터 검증·파싱은 각 서비스에서 수행해 계약별 code(SELLER/ORDER/PRODUCT_INVALID_PARAM)로 응답한다.
 */
@RestController
@RequestMapping("/api/seller")
@RequiredArgsConstructor
public class SellerController {

    private final SellerBrandResolver brandResolver;
    private final SellerSalesService sellerSalesService;
    private final SellerOrderService sellerOrderService;
    private final SellerProductService sellerProductService;

    /** S-1 — 자사 대시보드(주문상태·오늘지표·매출추이·재고부족·상품퍼널). from/to 생략 시 둘 다 오늘. */
    @GetMapping("/summary")
    public ApiResponse<SellerSummaryResponse> summary(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer lowStockThreshold,
            @RequestParam(required = false) Integer trendDays) {
        Brand brand = brandResolver.resolve(authUser.memberId());
        return ApiResponse.success(
                sellerSalesService.summary(brand, from, to, lowStockThreshold, trendDays));
    }

    /** S-2 — 자사 주문 목록(주문 단위). status: ORDERED|SHIPPING|DELIVERED|CLAIM. keyword는 MVP 미구현(무시). */
    @GetMapping("/orders")
    public ApiResponse<SellerOrderListResponse> orders(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        Brand brand = brandResolver.resolve(authUser.memberId());
        return ApiResponse.success(sellerOrderService.list(brand.getId(), status, page, size));
    }

    /** S-3 — 자사 상품 목록(판매자 화면용). status: ON_SALE|SOLD_OUT|HIDDEN(displayStatus 기준). q 없음(챗봇 I-9만 유지). */
    @GetMapping("/products")
    public ApiResponse<SellerProductListResponse> products(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "latest") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Brand brand = brandResolver.resolve(authUser.memberId());
        return ApiResponse.success(sellerProductService.list(brand.getId(), status, sort, page, size));
    }
}
