package com.jarvis.seller.dto;

import java.util.List;

/**
 * I-11 수정 결과 (노션 I-11) — 수정 후 현재값 echo,
 * changes는 change log 어휘 대문자 배열(PRICE/STOCK/STATUS만 — 로그 없는 필드 변경은 미포함).
 */
public record SellerProductUpdateResponse(Long productId, int price, int stockQuantity,
                                          String status, List<String> changes) {
}
