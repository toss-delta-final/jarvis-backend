package com.jarvis.review;

import com.jarvis.global.auth.AuthUser;
import com.jarvis.global.response.ApiResponse;
import com.jarvis.review.dto.ReviewCreateRequest;
import com.jarvis.review.dto.ReviewCreateResponse;
import com.jarvis.review.dto.ReviewListResponse;
import com.jarvis.review.dto.ReviewReportRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** P-3 목록 + M-1 작성·M-3 신고 (04 §2·§5) — /api/reviews/**는 USER 가드 (SecurityConfig) */
@RestController
@RequiredArgsConstructor
@Validated
public class ReviewController {

    private final ReviewService reviewService;

    @GetMapping("/api/products/{productId}/reviews")
    public ApiResponse<ReviewListResponse> productReviews(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size,
            @RequestParam(defaultValue = "latest") @Pattern(regexp = "latest|rating") String sort) {
        return ApiResponse.success(reviewService.getProductReviews(productId, page, size, sort));
    }

    @PostMapping("/api/reviews")
    public ApiResponse<ReviewCreateResponse> write(@Valid @RequestBody ReviewCreateRequest request,
                                                   @AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.success(reviewService.write(authUser.memberId(), request));
    }

    @PostMapping("/api/reviews/{id}/reports")
    public ApiResponse<Void> report(@PathVariable Long id,
                                    @Valid @RequestBody ReviewReportRequest request,
                                    @AuthenticationPrincipal AuthUser authUser) {
        reviewService.report(authUser.memberId(), id, request.reason());
        return ApiResponse.success(null);
    }
}
