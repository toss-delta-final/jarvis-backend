package com.jarvis.internal;

import com.jarvis.global.response.ApiResponse;
import com.jarvis.order.OrderService;
import com.jarvis.order.dto.InternalOrderListResponse;
import com.jarvis.order.dto.InternalOrderStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** I-4·I-19 (04 §10) — CS 챗봇용. userId는 채팅 티켓의 메아리 (05 §0-1) */
@RestController
@RequestMapping("/internal/members/{userId}")
@RequiredArgsConstructor
public class InternalOrderController {

    private final OrderService orderService;

    /** I-4 — 요약 전용, I-19(목록)와 역할 분담 (05 §I-4) */
    @GetMapping("/orders/status")
    public ApiResponse<InternalOrderStatusResponse> statusSummary(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "3") int recent) {
        return ApiResponse.success(orderService.statusSummary(userId, recent));
    }

    /** I-19 — 구매 이력 목록, status 단일 필터(우리 상태명 어휘) (05 §I-19) */
    @GetMapping("/orders")
    public ApiResponse<InternalOrderListResponse> list(
            @PathVariable Long userId,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(orderService.listForChat(userId, status));
    }
}
