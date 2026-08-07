package com.jarvis.internal.dto;

/**
 * I-26 응답 (노션 I-26) — 찜한 {@code productId}를 그대로 돌려준다(별도 wishlistId 없음 — 해제 키가
 * productId인 것과 정합).
 *
 * <p>공개 M-5와 <b>id 타입만</b> 다르다 — internal은 숫자 BIGINT 유지(§2.6). 공개
 * {@code WishlistAddResponse}에는 {@code @StringId}가 붙어 있어 재사용하면 문자열이 새어 나간다.
 */
public record InternalWishlistAddResponse(Long productId) {
}
