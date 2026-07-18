package com.jarvis.review.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** M-1 (04 §5) */
public record ReviewCreateRequest(
        @NotNull Long orderItemId,
        @Min(1) @Max(5) int rating,
        @NotBlank @Size(max = 2000) String content) {
}
