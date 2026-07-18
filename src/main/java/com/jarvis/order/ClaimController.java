package com.jarvis.order;

import com.jarvis.global.auth.AuthUser;
import com.jarvis.global.response.ApiResponse;
import com.jarvis.order.dto.ClaimListResponse;
import com.jarvis.order.dto.ClaimRequest;
import com.jarvis.order.dto.ClaimResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** O-5·O-6 (04 §4) — USER 가드는 SecurityConfig(/api/order-items/**, /api/claims/**) */
@RestController
@RequiredArgsConstructor
@Validated
public class ClaimController {

    private final ClaimService claimService;

    @PostMapping("/api/order-items/{id}/claims")
    public ApiResponse<ClaimResponse> request(@PathVariable Long id,
                                              @Valid @RequestBody ClaimRequest request,
                                              @AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.success(claimService.request(authUser.memberId(), id, request));
    }

    @GetMapping("/api/claims")
    public ApiResponse<ClaimListResponse> myClaims(@AuthenticationPrincipal AuthUser authUser,
                                                   @RequestParam(defaultValue = "0") @Min(0) int page,
                                                   @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return ApiResponse.success(claimService.myClaims(authUser.memberId(), page, size));
    }
}
