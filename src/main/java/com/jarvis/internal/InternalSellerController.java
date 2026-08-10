package com.jarvis.internal;

import com.jarvis.global.response.ApiResponse;
import com.jarvis.product.ProductStatus;
import com.jarvis.seller.AnalysisPeriod;
import com.jarvis.seller.SellerAnalyticsService;
import com.jarvis.seller.SellerOrderService;
import com.jarvis.seller.SellerProductService;
import com.jarvis.seller.SellerReviewService;
import com.jarvis.seller.SellerSalesService;
import com.jarvis.seller.dto.AccountEventAggregateResponse;
import com.jarvis.seller.dto.BrandAccountEventAggregateResponse;
import com.jarvis.seller.dto.SellerChurnResponse;
import com.jarvis.seller.dto.SellerCustomerFeaturesResponse;
import com.jarvis.seller.dto.SellerEventsResponse;
import com.jarvis.seller.dto.SellerFunnelResponse;
import com.jarvis.seller.dto.SellerOrderEventsResponse;
import com.jarvis.seller.dto.SellerOrderInternalResponse;
import com.jarvis.seller.dto.SellerProductChangesResponse;
import com.jarvis.seller.dto.SellerProductCreateRequest;
import com.jarvis.seller.dto.SellerProductCreateResponse;
import com.jarvis.seller.dto.SellerProductDeleteResponse;
import com.jarvis.seller.dto.SellerProductInternalListResponse;
import com.jarvis.seller.dto.SellerProductUpdateRequest;
import com.jarvis.seller.dto.SellerProductUpdateResponse;
import com.jarvis.seller.dto.SellerSalesResponse;
import com.jarvis.seller.dto.SellerShipRequest;
import com.jarvis.seller.dto.SellerShipResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
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
 * 판매자 콜백 I-6~I-16·I-38 (04 §10, 05 §1-3) — InternalTokenFilter가 지킨다(03 D4).
 * brandId는 FastAPI가 티켓 claim에서 코드 주입한 값(§1-0) — 그래도 상품 소유권은 매번 재검증.
 * 쓰기(I-10/I-11/I-12)는 HITL confirm 후에만 호출된다는 계약(05 §1-3).
 * 분석 API의 from/to는 전부 필수 — 누락·형식 오류·역전을 INVALID_PERIOD로 통일하려고
 * String으로 받아 AnalysisPeriod가 파싱한다.
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
@Validated
public class InternalSellerController {

    private final SellerSalesService sellerSalesService;
    private final SellerProductService sellerProductService;
    private final SellerAnalyticsService sellerAnalyticsService;
    private final SellerOrderService sellerOrderService;
    private final SellerReviewService sellerReviewService;

    /** I-31 기간 생략 시 기본 — 최근 7일(to=오늘, from=6일 전, 노션 I-31) */
    private static final int REVIEW_DEFAULT_DAYS = 7;

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

    /**
     * 구 I-8 전역 경로 — <b>삭제가 아니라 존치</b>다(노션 I-8 2026-08-06). 무차별 대입 탐지는 브랜드에
     * 귀속할 수 없는 플랫폼 보안 신호라 admin 파트가 부활하면 그대로 쓴다. 판매자 소비용은 아래
     * 브랜드 스코프 경로다 — 이쪽을 판매자 워커가 부르면 자사와 무관한 전체 신호를 보게 된다.
     */
    @GetMapping("/account-events")
    public ApiResponse<AccountEventAggregateResponse> accountEvents(
            @RequestParam(defaultValue = "eventType") String groupBy,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.success(
                sellerAnalyticsService.accountEvents(groupBy, eventType, AnalysisPeriod.of(from, to)));
    }

    /** I-8 — 자사 코호트 계정 이벤트 집계 (노션 I-8 2026-08-06 신규 경로, I-16 churn과 같은 코호트) */
    @GetMapping("/seller/{brandId}/account-events")
    public ApiResponse<BrandAccountEventAggregateResponse> brandAccountEvents(
            @PathVariable Long brandId,
            @RequestParam(defaultValue = "eventType") String groupBy,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.success(sellerAnalyticsService.brandAccountEvents(
                brandId, groupBy, eventType, AnalysisPeriod.of(from, to)));
    }

    /**
     * I-9 — 자사 상품 목록(수정 draft의 읽기 소스, S-3과 같은 서비스) — {rows, total} (노션 I-9).
     * DELETED는 status 미지정에서도 빠지고, status=DELETED로 물어도 빈 결과다.
     */
    @GetMapping("/seller/{brandId}/products")
    public ApiResponse<SellerProductInternalListResponse> products(
            @PathVariable Long brandId,
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return ApiResponse.success(sellerProductService.listInternal(brandId, status, q, limit, offset));
    }

    /**
     * I-29 — 자사 주문 조회, 현재 상태 스냅샷 (노션 I-29). S-2의 internal 판이며 발송 대상
     * orderItemId 해소 경로다. 기간은 선택 — 생략하면 전체 주문이다(현재 상태 Q&A라 기간 개념이 없고,
     * 잘라내면 오래된 미발송 주문이 빠져 I-30 대상 해소가 막힌다).
     * status·limit·offset 검증은 아래 애노테이션이 맡아 VALIDATION_ERROR로 떨어진다 — S-2의
     * ORDER_INVALID_PARAM과 코드가 다르다(노션 I-29 결정 1).
     */
    @GetMapping("/seller/{brandId}/orders")
    public ApiResponse<SellerOrderInternalResponse> orders(
            @PathVariable Long brandId,
            @RequestParam(required = false)
            @Pattern(regexp = "ORDERED|SHIPPING|DELIVERED|CLAIM") String status,
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) long offset) {
        return ApiResponse.success(sellerOrderService.listInternal(
                brandId, status, orderId, AnalysisPeriod.optional(from, to), limit, offset));
    }

    /**
     * I-31 — 자사 상품 리뷰 목록·집계 (노션 I-31). 읽기 전용이라 HITL이 없다.
     * status=VISIBLE만 — 신고로 숨겨진 리뷰를 에이전트가 인용하면 사고다.
     * 기간은 선택이고 생략하면 최근 7일이다(리뷰는 기간 질의가 본령이라 I-29의 "전체"와 반대).
     * stats=true면 목록 대신 집계만 내려가며 응답 shape 자체가 갈린다.
     */
    @GetMapping("/seller/{brandId}/reviews")
    public ApiResponse<?> reviews(
            @PathVariable Long brandId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String rating,
            @RequestParam(defaultValue = "latest") @Pattern(regexp = "latest|ratingAsc") String sort,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            @RequestParam(defaultValue = "false") boolean stats) {
        AnalysisPeriod period = AnalysisPeriod.withDefaultDays(from, to, REVIEW_DEFAULT_DAYS);
        return ApiResponse.success(stats
                ? sellerReviewService.stats(brandId, productId, rating, period)
                : sellerReviewService.list(brandId, productId, rating, sort, period, limit, offset));
    }

    /**
     * I-30 — 발송 처리, 주문 아이템 상태 전이 (노션 I-30). **HITL confirm 후에만 호출**된다(I-12와 같은 등급).
     * MVP 허용 전이는 ORDERED→SHIPPING 하나뿐이고, 이 API가 그 전이의 유일한 트리거다(01 D4 개정).
     * 복수 발송은 항목별 반복 호출 — bulk 없음(C-4·I-24 전례).
     */
    @PatchMapping("/seller/{brandId}/order-items/{orderItemId}/status")
    public ApiResponse<SellerShipResponse> shipOrderItem(
            @PathVariable Long brandId,
            @PathVariable Long orderItemId,
            @Valid @RequestBody SellerShipRequest request) {
        return ApiResponse.success(sellerOrderService.shipItem(brandId, orderItemId, request));
    }

    /** I-10 — 상품 등록(HITL confirm 후) — 등록은 change log 미기록, 201 {productId, status} (노션 I-10) */
    @PostMapping("/seller/{brandId}/products")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SellerProductCreateResponse> createProduct(
            @PathVariable Long brandId,
            @Valid @RequestBody SellerProductCreateRequest request) {
        return ApiResponse.success(sellerProductService.create(brandId, request));
    }

    /**
     * I-11 — 상품 수정 통합(HITL confirm 후) — 가격·설명·상태·재고, 소유 아님 404 (노션 I-11).
     * 삭제된 상품은 409 PRODUCT_DELETED, status=DELETED 지정도 거부(삭제는 I-12 전용 경로).
     */
    @PatchMapping("/seller/{brandId}/products/{productId}")
    public ApiResponse<SellerProductUpdateResponse> updateProduct(
            @PathVariable Long brandId,
            @PathVariable Long productId,
            @Valid @RequestBody SellerProductUpdateRequest request) {
        return ApiResponse.success(sellerProductService.updateInternal(brandId, productId, request));
    }

    /** I-12 — soft delete(DELETED 전환)만, 이미 DELETED면 409 ALREADY_DELETED. HIDDEN→DELETED는 정상 전이 */
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

    /**
     * I-38 — 고객 행동 피처 집계(세그멘테이션 입력 — 노션 I-38, 2026-08-10 확정).
     * 코호트는 I-16과 같은 조인이고 회원 노출은 I-14·I-16과 같은 HMAC 라벨뿐이다.
     */
    @GetMapping("/seller/{brandId}/customer-features")
    public ApiResponse<SellerCustomerFeaturesResponse> customerFeatures(
            @PathVariable Long brandId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.success(
                sellerAnalyticsService.customerFeatures(brandId, AnalysisPeriod.of(from, to)));
    }
}
