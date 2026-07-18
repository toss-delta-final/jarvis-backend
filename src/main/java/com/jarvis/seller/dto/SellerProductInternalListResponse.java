package com.jarvis.seller.dto;

import java.util.List;

/** I-9 챗봇용 목록 envelope (노션 I-9) — data = {rows, total(필터 적용 전체 건수)} */
public record SellerProductInternalListResponse(List<SellerProductItemResponse> rows, long total) {
}
