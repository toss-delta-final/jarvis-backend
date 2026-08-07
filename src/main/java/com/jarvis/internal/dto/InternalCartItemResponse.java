package com.jarvis.internal.dto;

import com.jarvis.cart.dto.CartItemResponse;

/**
 * I-2·I-25 응답 (노션 I-2·I-25) — 공개 C-2·C-3와 <b>id 타입만</b> 다르다.
 *
 * <p>공개 응답은 id를 문자열로 내보내지만(04 §0 — JS 안전 정수 초과) internal은 숫자 BIGINT를
 * 유지한다(노션 I-25 §2.6). Python은 정밀도 손실이 없어 바꿀 이유가 없고, 바꾸면 LLM 팀의
 * Pydantic 스키마를 이득 없이 흔든다.
 *
 * <p><b>공개 DTO를 그대로 쓰지 않는 이유</b> — {@code CartItemResponse}에는 FE 계약용
 * {@code @StringId}가 붙어 있어, 재사용하면 그 문자열이 internal 레인까지 새어 나간다.
 * I-18이 {@link InternalCartResponse}를 따로 둔 것과 같은 처리다.
 */
public record InternalCartItemResponse(Long cartItemId, int quantity) {

    public static InternalCartItemResponse from(CartItemResponse item) {
        return new InternalCartItemResponse(item.cartItemId(), item.quantity());
    }
}
