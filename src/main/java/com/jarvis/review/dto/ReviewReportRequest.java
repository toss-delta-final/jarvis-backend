package com.jarvis.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** M-3 (04 §5) */
public record ReviewReportRequest(@NotBlank @Size(max = 500) String reason) {
}
