package com.jarvis.inquiry;

import com.jarvis.global.auth.AuthUser;
import com.jarvis.global.response.ApiResponse;
import com.jarvis.inquiry.dto.InquiryListResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** M-9 (04 §5) — /api/inquiries/**는 USER 가드 (SecurityConfig) */
@RestController
@RequiredArgsConstructor
@Validated
public class InquiryController {

    private final InquiryService inquiryService;

    @GetMapping("/api/inquiries/me")
    public ApiResponse<InquiryListResponse> myInquiries(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return ApiResponse.success(inquiryService.myInquiries(authUser.memberId(), page, size));
    }
}
