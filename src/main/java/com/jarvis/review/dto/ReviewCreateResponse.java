package com.jarvis.review.dto;

import com.jarvis.review.Review;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** M-1 응답 — 날짜는 ISO 8601 + 오프셋 (03 D2) */
public record ReviewCreateResponse(Long reviewId, Long orderItemId, Long productId,
                                   int rating, String content, OffsetDateTime createdAt) {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    public static ReviewCreateResponse from(Review review) {
        return new ReviewCreateResponse(review.getId(), review.getOrderItemId(),
                review.getProductId(), review.getRating(), review.getContent(),
                review.getCreatedAt().atZone(ZONE).toOffsetDateTime());
    }
}
