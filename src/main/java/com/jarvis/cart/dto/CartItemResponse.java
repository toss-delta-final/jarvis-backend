package com.jarvis.cart.dto;

/** C-2·C-3 응답 — upsert/변경 결과 수량 */
public record CartItemResponse(Long cartItemId, int quantity) {
}
