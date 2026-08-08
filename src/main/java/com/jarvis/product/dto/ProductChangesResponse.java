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

    /**
     * {@code brandId}는 2026-08-07 AI팀 요청으로 추가 — 이름({@code brand})과 역할이 다르다.
     * 이름은 표시·검색 입력이고 이건 조인 키라 둘 다 싣는다. internal 계약이라 <b>숫자 BIGINT</b>이며
     * 공개 API의 문자열 id 규약({@code @StringId})을 붙이지 않는다(05 §2.6).
     * {@code description}은 2026-08-08 AI팀 요청으로 추가 — <b>I-17 한정</b>이라 I-1 등 다른 응답은 그대로다.
     * 컬럼이 NULL이면 NON_NULL로 키가 빠진다(빈 문자열로 채우지 않는다).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(Long productId, String status, String name, String description, String category,
                       Long brandId, String brand,
                       Integer price, Double rating, Long reviewCount, JsonNode attributes,
                       OffsetDateTime updatedAt) {

        public static Item onSale(Long productId, OffsetDateTime updatedAt, String name, String description,
                                  String category, Long brandId, String brand, int price, double rating,
                                  long reviewCount, JsonNode attributes) {
            return new Item(productId, "ON_SALE", name, description, category, brandId, brand, price, rating,
                    reviewCount, attributes, updatedAt);
        }

        public static Item hidden(Long productId, OffsetDateTime updatedAt) {
            return new Item(productId, "HIDDEN", null, null, null, null, null, null, null, null, null,
                    updatedAt);
        }
    }
}
