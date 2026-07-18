package com.jarvis.review.dto;

/** P-2·카드용 평점 통계 — 저장 금지, 조회 시 집계 (02 D9) */
public record RatingStats(long count, double average) {

    public static final RatingStats EMPTY = new RatingStats(0, 0.0);

    public static RatingStats of(long count, Double average) {
        if (count == 0 || average == null) {
            return EMPTY;
        }
        return new RatingStats(count, Math.round(average * 10) / 10.0);
    }
}
