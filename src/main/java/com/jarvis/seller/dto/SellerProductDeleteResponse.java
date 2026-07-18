package com.jarvis.seller.dto;

/** I-12 soft delete 결과 (노션 I-12) — data = {productId, status:"HIDDEN"} */
public record SellerProductDeleteResponse(Long productId, String status) {
}
