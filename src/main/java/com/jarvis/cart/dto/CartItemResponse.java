package com.jarvis.cart.dto;

import com.jarvis.global.response.StringId;

/** C-2·C-3 응답 — upsert/변경 결과 수량 */
public record CartItemResponse(@StringId Long cartItemId, int quantity) {
}
