package com.jarvis.seller.dto;

/** I-10 등록 결과 (노션 I-10) — 201 Created, data = {productId, status} */
public record SellerProductCreateResponse(Long productId, String status) {
}
