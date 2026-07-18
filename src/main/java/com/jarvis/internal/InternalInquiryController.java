package com.jarvis.internal;

import com.jarvis.global.response.ApiResponse;
import com.jarvis.inquiry.InquiryService;
import com.jarvis.internal.dto.InternalInquiryCreateRequest;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** I-5 (04 §10) — 문의 생성의 유일한 경로(문의 단일 채널 원칙, 05 §I-5) */
@RestController
@RequiredArgsConstructor
public class InternalInquiryController {

    private final InquiryService inquiryService;

    @PostMapping("/internal/inquiries")
    public ApiResponse<Map<String, Long>> create(@Valid @RequestBody InternalInquiryCreateRequest request) {
        Long inquiryId = inquiryService.create(request.userId(), request.title(), request.content());
        return ApiResponse.success(Map.of("inquiryId", inquiryId));
    }
}
