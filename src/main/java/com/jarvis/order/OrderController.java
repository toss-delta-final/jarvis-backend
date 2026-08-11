package com.jarvis.order;

import com.jarvis.global.auth.AuthUser;
import com.jarvis.global.response.ApiResponse;
import com.jarvis.order.dto.OrderCreateRequest;
import com.jarvis.order.dto.OrderCreateResponse;
import com.jarvis.order.dto.OrderDetailResponse;
import com.jarvis.order.dto.OrderListResponse;
import com.jarvis.order.dto.RetryPaymentRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** O-1~O-4 (04 §4) — 전부 로그인 필수(USER 가드는 SecurityConfig). 게스트는 결제 진입 시 FE가 로그인 유도 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Validated
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ApiResponse<OrderCreateResponse> create(@Valid @RequestBody OrderCreateRequest request,
                                                   @AuthenticationPrincipal AuthUser authUser,
                                                   // 분석용 세션 키 — 신원 아님(노션 O-1 2026-08-11, C-2와 동일).
                                                   // 누락 시 결제는 정상, purchase_complete 적재만 스킵
                                                   @RequestHeader(value = "X-Session-Key", required = false)
                                                   String sessionKey) {
        return ApiResponse.success(orderService.create(authUser.memberId(), request, sessionKey));
    }

    @PostMapping("/{id}/retry-payment")
    public ApiResponse<OrderCreateResponse> retryPayment(@PathVariable Long id,
                                                         @Valid @RequestBody RetryPaymentRequest request,
                                                         @AuthenticationPrincipal AuthUser authUser,
                                                         @RequestHeader(value = "X-Session-Key", required = false)
                                                         String sessionKey) {
        return ApiResponse.success(orderService.retryPayment(authUser.memberId(), id, request, sessionKey));
    }

    @GetMapping
    public ApiResponse<OrderListResponse> list(@AuthenticationPrincipal AuthUser authUser,
                                               @RequestParam(defaultValue = "0") @Min(0) int page,
                                               @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return ApiResponse.success(orderService.list(authUser.memberId(), page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<OrderDetailResponse> detail(@PathVariable Long id,
                                                   @AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.success(orderService.detail(authUser.memberId(), id));
    }
}
