package com.jarvis.seller.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * I-31 목록 모드 (노션 I-31) — status=VISIBLE만 담는다. 신고로 숨겨진 리뷰를 에이전트가
 * 인용하면 사고이므로, 구매자 노출(P-3)과 같은 진실만 보게 한다.
 * 리뷰 0건도 200 + 빈 rows·total 0이 정상이다(전부 숨김이어도 같다).
 * id는 숫자 그대로 — 문자열화는 FE가 보는 공개 응답 한정(04 2026-08-06).
 */
public record SellerReviewListResponse(List<Row> rows, long total) {

    public record Row(Long reviewId, Long productId, String productName, int rating, String content,
                      String authorNickname, OffsetDateTime createdAt) {
    }
}
