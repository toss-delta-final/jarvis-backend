package com.jarvis.address.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** M-8 등록 (04 §5) — isDefault null은 false 취급 */
public record AddressCreateRequest(
        @NotBlank @Size(max = 50) String label,
        @NotBlank @Size(max = 50) String recipient,
        @NotBlank @Size(max = 20) String phone,
        @NotBlank @Size(max = 10) String zipCode,
        @NotBlank @Size(max = 255) String address1,
        @Size(max = 255) String address2,
        Boolean isDefault) {

    public boolean wantsDefault() {
        return Boolean.TRUE.equals(isDefault);
    }
}
