package com.jarvis.seller.dto;

import java.time.LocalDate;
import java.util.List;

/** I-8 계정 이벤트 집계 (04 §10) — 전역(brandId 스코프 아님), IP 마스킹, 집계 전용(raw 미반환) */
public record AccountEventAggregateResponse(String groupBy, String eventType, LocalDate from,
                                            LocalDate to, List<Bucket> buckets) {

    public record Bucket(String key, long count) {
    }
}
