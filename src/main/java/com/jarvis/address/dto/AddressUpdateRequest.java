package com.jarvis.address.dto;

import jakarta.validation.constraints.Size;

/**
 * M-8 수정 (04 §5) — PATCH: null 필드는 유지.
 * isDefault는 true만 의미 있음(기본 지정) — false로 기본 해제는 불가, 다른 주소를 기본 지정으로만 이동.
 */
public record AddressUpdateRequest(
        @Size(max = 50) String label,
        @Size(max = 50) String recipient,
        @Size(max = 20) String phone,
        @Size(max = 10) String zipCode,
        @Size(max = 255) String address1,
        @Size(max = 255) String address2,
        Boolean isDefault) {

    public boolean wantsDefault() {
        return Boolean.TRUE.equals(isDefault);
    }
}
