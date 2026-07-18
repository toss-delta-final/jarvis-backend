package com.jarvis.product.dto;

import java.util.List;

/** 카드 목록 공통 래퍼 — data.items (노션 명세 P-4·M-7·M-4, 2026-07-18 정합화) */
public record ProductCardListResponse(List<ProductCardResponse> items) {
}
