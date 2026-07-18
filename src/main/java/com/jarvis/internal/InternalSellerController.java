package com.jarvis.internal;

import com.jarvis.global.response.ApiResponse;
import com.jarvis.product.ProductStatus;
import com.jarvis.seller.AnalysisPeriod;
import com.jarvis.seller.SellerAnalyticsService;
import com.jarvis.seller.SellerProductService;
import com.jarvis.seller.SellerSalesService;
import com.jarvis.seller.dto.AccountEventAggregateResponse;
import com.jarvis.seller.dto.SellerChurnResponse;
import com.jarvis.seller.dto.SellerEventsResponse;
import com.jarvis.seller.dto.SellerFunnelResponse;
import com.jarvis.seller.dto.SellerOrderEventsResponse;
import com.jarvis.seller.dto.SellerProductChangesResponse;
import com.jarvis.seller.dto.SellerProductCreateRequest;
import com.jarvis.seller.dto.SellerProductCreateResponse;
import com.jarvis.seller.dto.SellerProductDeleteResponse;
import com.jarvis.seller.dto.SellerProductInternalListResponse;
import com.jarvis.seller.dto.SellerProductUpdateRequest;
import com.jarvis.seller.dto.SellerProductUpdateResponse;
import com.jarvis.seller.dto.SellerSalesResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 판매자 콜백 I-6~I-16 (04 §10, 05 §1-3) — InternalTokenFilter가 지킨다(03 D4).
 * brandId는 FastAPI가 티켓 claim에서 코드 주입한 값(§1-0) — 그래도 상품 소유권은 매번 재검증.
 * 쓰기(I-10/I-11/I-12)는 HITL confirm 후에만 호출된다는 계약(05 §1-3).
 * 분석 API의 from/to는 전부 필수 — 누락·형식 오류·역전을 INVALID_PERIOD로 통일하려고
 * String으로 받아 AnalysisPeriod가 파싱한다.
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalSellerController {

    private final SellerSalesService sellerSalesService;
    private final SellerProductService sellerProductService;
    private final SellerAnalyticsService sellerAnalyticsService;

    /** I-6 — 매출 시계열(granularity daily|weekly|monthly|summary, 이상 감지 포함) */
    @GetMapping("/seller/{brandId}/sales")
    public ApiResponse<SellerSalesResponse> sales(
            @PathVariable Long brandId,
            @RequestParam(defaultValue = "daily") String granularity,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.success(
                sellerSalesService.sales(brandId, granularity, AnalysisPeriod.of(from, to)));
    }

    /** I-7 — 구매전환 퍼널 4단(stages+conversionRates) */
    @GetMapping("/seller/{brandId}/funnel")
    public ApiResponse<SellerFunnelResponse> funnel(
            @PathVariable Long brandId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.success(
                sellerAnalyticsService.funnel(brandId, AnalysisPeriod.of(from, to)));
    }

    /** I-8 — 계정 이벤트 집계(전역 — brandId 스코프 아님, groupBy=ip는 무차별 대입 신호) */
    @GetMapping("/account-events")
    public ApiResponse<AccountEventAggregateResponse> accountEvents(
            @RequestParam(defaultValue = "eventType") String groupBy,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.success(
                sellerAnalyticsService.accountEvents(groupBy, eventType, AnalysisPeriod.of(from, to)));
    }

    /** I-9 — 자사 상품 목록(수정 draft의 읽기 소스, S-3과 같은 서비스) — {rows, total} (노션 I-9) */
    @GetMapping("/seller/{brandId}/products")
    public ApiResponse<SellerProductInternalListResponse> products(
            @PathVariable Long brandId,
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return ApiResponse.success(sellerProductService.listInternal(brandId, status, q, limit, offset));
    }

    /** I-10 — 상품 등록(HITL confirm 후) — 등록은 change log 미기록, 201 {productId, status} (노션 I-10) */
    @PostMapping("/seller/{brandId}/products")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SellerProductCreateResponse> createProduct(
            @PathVariable Long brandId,
            @Valid @RequestBody SellerProductCreateRequest request) {
        return ApiResponse.success(sellerProductService.create(brandId, request));
    }

    /** I-11 — 상품 수정 통합(HITL confirm 후) — S-5와 같은 검증·change log, 소유 아님 404 (노션 I-11) */
    @PatchMapping("/seller/{brandId}/products/{productId}")
    public ApiResponse<SellerProductUpdateResponse> updateProduct(
            @PathVariable Long brandId,
            @PathVariable Long productId,
            @Valid @RequestBody SellerProductUpdateRequest request) {
        return ApiResponse.success(sellerProductService.updateInternal(brandId, productId, request));
    }

    /** I-12 — soft delete(HIDDEN 전환)만, 이미 HIDDEN이면 409 ALREADY_HIDDEN (노션 I-12, 2026-07-18 결정) */
    @DeleteMapping("/seller/{brandId}/products/{productId}")
    public ApiResponse<SellerProductDeleteResponse> deleteProduct(
            @PathVariable Long brandId, @PathVariable Long productId) {
        return ApiResponse.success(sellerProductService.softDelete(brandId, productId));
    }

    /** I-13 — 행동 이벤트 집계(groupBy=product|eventType|date, 상품 연계 4종 — 노션 I-13) */
    @GetMapping("/seller/{brandId}/events")
    public ApiResponse<SellerEventsResponse> events(
            @PathVariable Long brandId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) Long productId,
            @RequestParam(defaultValue = "product") String groupBy,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.success(sellerAnalyticsService.events(brandId, eventType, productId,
                groupBy, AnalysisPeriod.of(from, to)));
    }

    /** I-14 — 주문 상태 전이 로그(toStatus 복수/actorType/stats/groupBy=memberId) */
    @GetMapping("/seller/{brandId}/order-events")
    public ApiResponse<SellerOrderEventsResponse> orderEvents(
            @PathVariable Long brandId,
            @RequestParam(required = false) String toStatus,
            @RequestParam(required = false) String actorType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "false") boolean stats,
            @RequestParam(required = false) String groupBy,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(sellerAnalyticsService.orderEvents(brandId, toStatus, actorType,
                AnalysisPeriod.of(from, to), stats, groupBy, limit));
    }

    /** I-15 — 상품 변경 이력(품절 신호 = STOCK newValue "0") */
    @GetMapping("/seller/{brandId}/product-changes")
    public ApiResponse<SellerProductChangesResponse> productChanges(
            @PathVariable Long brandId,
            @RequestParam(required = false) String changeType,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(sellerAnalyticsService.productChanges(brandId, changeType, productId,
                AnalysisPeriod.of(from, to), limit));
    }

    /** I-16 — 이탈 코호트(기간 내 자사 상품 상호작용 회원, behavior_events 무활동 기준 — 노션 I-16) */
    @GetMapping("/seller/{brandId}/churn")
    public ApiResponse<SellerChurnResponse> churn(
            @PathVariable Long brandId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "30") int inactiveDays) {
        return ApiResponse.success(
                sellerAnalyticsService.churn(brandId, AnalysisPeriod.of(from, to), inactiveDays));
    }
}
