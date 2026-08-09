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
        // 구 필드 (2026-08-09 이전) — 판매자 챗봇이 stocks로 옮길 때까지 계속 받는다.
        // 우리가 먼저 배포해 깨뜨린 것이라 전환 비용을 상대에게 떠넘기지 않는다. 전부 옮긴 뒤 제거한다
        Integer stockQuantity,
        Long categoryId,
        @Size(max = 500) String summary,
        JsonNode attributes,
        String description,
        @Size(max = 500) String imageUrl,
        ProductStatus status) {

    /**
     * 구 {@code stockQuantity}를 {@code stocks} 한 줄로 환산한다 (하위호환, 2026-08-09).
     * 옵션 없는 상품에서 둘은 <b>완전히 같은 뜻</b>이라 환산이 손실 없이 된다.
     * 둘 다 오면 새 필드가 이긴다 — 값이 다를 때 옛 값을 쓸 이유가 없다.
     */
    public List<StockInput> resolvedStocks() {
        if (stocks != null) {
            return stocks;
        }
        return stockQuantity == null ? null : List.of(new StockInput(null, stockQuantity));
    }
}
