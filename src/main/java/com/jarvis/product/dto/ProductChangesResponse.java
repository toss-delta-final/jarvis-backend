package com.jarvis.product.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * I-17 상품 변경분 배치 응답 (05 §I-17) — items는 (updatedAt ASC, productId ASC).
 * ON_SALE은 생성물 계산 입력 전체, HIDDEN은 productId·status·updatedAt만 싣는다(NON_NULL로 생략).
 * nextCursor는 마지막 항목의 불투명 커서(빈 결과면 요청 since 그대로), hasMore=true면 반드시 존재.
 */
public record ProductChangesResponse(List<Item> items, String nextCursor, boolean hasMore) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(Long productId, String status, String name, String category, String brand,
                       Integer price, Double rating, Long reviewCount, JsonNode attributes,
                       OffsetDateTime updatedAt) {

        public static Item onSale(Long productId, OffsetDateTime updatedAt, String name, String category,
                                  String brand, int price, double rating, long reviewCount,
                                  JsonNode attributes) {
            return new Item(productId, "ON_SALE", name, category, brand, price, rating, reviewCount,
                    attributes, updatedAt);
        }

        public static Item hidden(Long productId, OffsetDateTime updatedAt) {
            return new Item(productId, "HIDDEN", null, null, null, null, null, null, null, updatedAt);
        }
    }
}
