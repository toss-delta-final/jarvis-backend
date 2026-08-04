package com.jarvis.product.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jarvis.product.Product;
import com.jarvis.review.dto.RatingStats;
import java.util.List;

/**
 * I-1/I-3 후보 필드 (05 §I-1) — 리랭킹 계산 입력인 price·rating·reviewCount는 포함하고(2026-07-27),
 * 나머지 display 필드(originalPrice·imageUrl)는 카드 조회(CH-5) 소관.
 * attributes는 LLM 후처리 필터링용 (02 D7).
 *
 * <p>{@code options}는 <b>옵션명만</b> 싣는다(2026-08-03 LLM팀 합의) — 담기용 {@code optionId}는
 * I-2가 400 + options 목록으로 되돌려주는 경로가 이미 있어 여기선 불필요하다. 상한을 두는 근거는
 * 실측이다 — 옵션 수 p99=40·max=161이고 <b>20개를 넘는 2.67%가 옵션 바이트의 44.5%</b>를 차지한다.
 * 잘렸는지는 {@code optionCount}(자르기 전 전체 개수)로 판별한다.
 */
public record ProductCandidateResponse(Long productId, String name, String summary,
                                       JsonNode attributes, String categoryName, String brandName,
                                       int price, double rating, long reviewCount,
                                       List<String> options, int optionCount) {

    public static ProductCandidateResponse from(Product product, JsonNode attributes,
                                                String categoryName, String brandName,
                                                RatingStats stats, List<String> options,
                                                int optionCount) {
        return new ProductCandidateResponse(product.getId(), product.getName(), product.getSummary(),
                attributes, categoryName, brandName, product.getPrice(),
                stats.average(), stats.count(), options, optionCount);
    }
}
