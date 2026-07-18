package com.jarvis.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * O-1 (04 §4) — source는 cartItemIds(장바구니 경유) 또는 items(바로 구매) 중 정확히 하나,
 * 배송지는 addressId 또는 address 직접 입력 중 정확히 하나 — 서비스 검증.
 * 금액 필드는 없다 — 결제 금액의 진실은 서버 스냅샷 재계산 (02 D26④).
 */
public record OrderCreateRequest(
        List<Long> cartItemIds,
        @Valid List<OrderLine> items,
        Long addressId,
        @Valid AddressInput address,
        @Size(max = 200, message = "배송 요청사항은 200자 이하여야 합니다.") String deliveryRequest,
        @NotBlank(message = "결제 수단은 필수입니다.")
        @Size(max = 30, message = "결제 수단이 올바르지 않습니다.") String paymentMethod) {

    /** 바로 구매 라인 — 검증 규칙은 장바구니 경유와 동일 (04 §4 두 경로 공통) */
    public record OrderLine(
            @NotNull(message = "상품 ID는 필수입니다.") Long productId,
            Long optionId,
            @NotNull(message = "수량은 필수입니다.")
            @Min(value = 1, message = "수량은 1 이상이어야 합니다.")
            @Max(value = 99, message = "수량은 99 이하여야 합니다.") Integer quantity) {
    }

    /** 배송지 직접 입력 — orders 스냅샷 컬럼과 동일 형상 */
    public record AddressInput(
            @NotBlank(message = "수령인은 필수입니다.") @Size(max = 50) String recipient,
            @NotBlank(message = "연락처는 필수입니다.") @Size(max = 20) String phone,
            @NotBlank(message = "우편번호는 필수입니다.") @Size(max = 10) String zipCode,
            @NotBlank(message = "주소는 필수입니다.") @Size(max = 255) String address1,
            @Size(max = 255) String address2) {
    }
}
