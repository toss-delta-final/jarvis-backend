package com.jarvis.internal.dto;

import jakarta.validation.constraints.NotNull;

/** I-26 요청 (05 §I-26) — userId는 채팅 티켓의 메아리. 찜은 회원 전용이라 guestId가 없다(M-4) */
public record InternalWishlistAddRequest(@NotNull Long userId, @NotNull Long productId) {
}
