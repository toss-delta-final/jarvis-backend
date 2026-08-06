package com.jarvis.seller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * I-30 발송 처리 요청 (노션 I-30) — HITL confirm 후에만 호출된다.
 * toStatus는 상태기계(01) 아이템 어휘이고 MVP 유효값은 SHIPPING 하나뿐이다.
 * 어휘 밖(PREPARING·SHIPPED 포함)이면 400 VALIDATION_ERROR, 어휘엔 있으나 MVP 미허용이면
 * 400 ORDER_INVALID_TRANSITION으로 갈린다 — 둘의 구분이 계약이다.
 */
public record SellerShipRequest(@NotBlank String toStatus,
                                @Size(max = 200) String reason) {
}
