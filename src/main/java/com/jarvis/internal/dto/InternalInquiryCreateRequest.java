package com.jarvis.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** I-5 요청 (05 §I-5) — title·content 모두 LLM 생성 (02 D23). userId null(게스트)은 서비스에서 403 */
public record InternalInquiryCreateRequest(Long userId,
                                           @NotBlank @Size(max = 200) String title,
                                           @NotBlank String content) {
}
