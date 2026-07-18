package com.jarvis.order.dto;

import com.jarvis.order.ClaimType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** O-5 (04 §4) — type은 CANCEL/RETURN 2종(교환 제거 확정 — 01 D11), reason은 선택 */
public record ClaimRequest(
        @NotNull(message = "신청 유형은 필수입니다.") ClaimType type,
        @Size(max = 500, message = "사유는 500자 이하여야 합니다.") String reason) {
}
