package com.jarvis.internal.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** I-2 요청 (05 §I-2) — userId/guestId는 채팅 티켓의 메아리, 둘 중 하나 필수. 검증은 C-2와 동일 */
public record InternalCartAddRequest(Long userId, String guestId,
                                     @NotNull Long productId, Long optionId,
                                     @NotNull @Min(1) @Max(99) Integer quantity) {

    @AssertTrue(message = "userId 또는 guestId 중 하나가 필요합니다")
    public boolean isIdentityPresent() {
        return userId != null || (guestId != null && !guestId.isBlank());
    }
}
