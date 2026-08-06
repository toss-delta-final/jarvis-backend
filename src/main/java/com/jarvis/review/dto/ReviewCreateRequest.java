package com.jarvis.review.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** M-1 (04 §5). content는 선택 — 별점만 남기기 허용 (2026-08-06) */
public record ReviewCreateRequest(
        @NotNull Long orderItemId,
        @Min(1) @Max(5) int rating,
        @Size(max = 2000) String content) {

    /** 생략·공백만은 "내용 없음"과 같게 취급 — 빈 문자열을 저장하지 않는다 */
    public String normalizedContent() {
        return content == null || content.isBlank() ? null : content;
    }
}
