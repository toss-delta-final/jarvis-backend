package com.jarvis.seller.dto;

import java.util.List;
import java.util.Map;

/**
 * I-31 집계 모드(stats=true) (노션 I-31) — 목록 대신 집계만 내려간다(rows·total 생략).
 * from/to·rating·productId 필터는 집계에도 전부 적용된다 — 그래야 "1–2점이 어느 상품에
 * 몰렸어?"가 성립한다.
 *
 * <p>averageRating은 리뷰 0건이면 null이다 — 0이 아니다(I-16 churnRate 규칙과 같은 이유로
 * "평점 0점"으로 오독되면 안 된다). distribution은 5~1 키를 항상 전부 담는다(0도 포함) —
 * P-3와 같은 모양이라 LLM이 키 부재를 처리할 필요가 없다.
 */
public record SellerReviewStatsResponse(long totalCount, Double averageRating,
                                        Map<String, Long> distribution, List<ByProduct> byProduct) {

    public record ByProduct(Long productId, String productName, long count, Double averageRating) {
    }
}
