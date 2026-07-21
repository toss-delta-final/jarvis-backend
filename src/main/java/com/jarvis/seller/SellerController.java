package com.jarvis.seller;

import com.jarvis.brand.Brand;
import com.jarvis.global.auth.AuthUser;
import com.jarvis.global.response.ApiResponse;
import com.jarvis.product.ProductStatus;
import com.jarvis.seller.dto.SellerOrderListResponse;
import com.jarvis.seller.dto.SellerProductListResponse;
import com.jarvis.seller.dto.SellerSummaryResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-1/S-2/S-3 (04 §7) — /api/seller/**는 SecurityConfig에서 ROLE_SELLER 가드.
 * brandId는 항상 JWT의 memberId → brand.seller_id 도출(클라이언트 주장 무시).
 * 상품 수정은 챗봇 경로(I-11, HITL)만 — 판매자 직접 수정(구 S-5)은 미채택.
 */
@RestController
@RequestMapping("/api/seller")
@RequiredArgsConstructor
@Validated
public class SellerController {

    private final SellerBrandResolver brandResolver;
    private final SellerSalesService sellerSalesService;
    private final SellerOrderService sellerOrderService;
    private final SellerProductService sellerProductService;

    /** S-1 — 자사 요약(기간별 매출/주문수 + 상품별 조회수·담김수·판매수) */
    @GetMapping("/summary")
    public ApiResponse<SellerSummaryResponse> summary(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Brand brand = brandResolver.resolve(authUser.memberId());
        return ApiResponse.success(sellerSalesService.summary(brand, from, to));
    }

    /** S-2 — 자사 아이템 단위 주문 목록 */
    @GetMapping("/orders")
    public ApiResponse<SellerOrderListResponse> orders(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        Brand brand = brandResolver.resolve(authUser.memberId());
        return ApiResponse.success(sellerOrderService.list(brand.getId(), page, size));
    }

    /** S-3 — 자사 상품 목록(판매자 화면용, I-9와 같은 서비스) */
    @GetMapping("/products")
    public ApiResponse<SellerProductListResponse> products(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "latest") String sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        Brand brand = brandResolver.resolve(authUser.memberId());
        return ApiResponse.success(sellerProductService.list(brand.getId(), status, q, sort, page, size));
    }
}
