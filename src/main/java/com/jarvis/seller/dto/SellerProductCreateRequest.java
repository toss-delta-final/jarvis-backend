package com.jarvis.seller.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jarvis.product.ProductStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * I-10 상품 등록 (04 §10, 노션 I-10) — name·price·stocks·categoryId 필수 검증은
 * 서비스 소관(422 MISSING_FIELD — bean validation 400 대신), stocks[].quantity ≥ 0도 서비스(422 INVALID_STOCK).
 * <b>등록 시점엔 옵션이 없으므로 stocks는 optionId=null 한 줄만 허용</b>한다 — 이 API는 옵션을 만들지 않는다
 * (2026-08-09, 구 stockQuantity 정수 대체).
 * originalPrice 생략 시 price와 동일(무할인), imageUrl 생략 시 플레이스홀더.
 * attributes는 JSON 객체(노션) — 저장 시 서비스가 문자열로 직렬화.
 */
public record SellerProductCreateRequest(
        @Size(max = 200) String name,
        @Min(0) Integer price,
        @Min(0) Integer originalPrice,
        List<StockInput> stocks,
        Long categoryId,
        @Size(max = 500) String summary,
        JsonNode attributes,
        String description,
        @Size(max = 500) String imageUrl,
        ProductStatus status) {
}
