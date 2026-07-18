package com.jarvis.seller.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * I-14 주문 상태 전이 로그 (04 §10) — 상태 어휘는 우리 상태명(01) 그대로.
 * statusCounts는 stats=true, memberCounts는 groupBy=memberId일 때만(그 외 null).
 */
public record SellerOrderEventsResponse(Long brandId, LocalDate from, LocalDate to, List<Item> items,
                                        Map<String, Long> statusCounts, List<MemberCount> memberCounts) {

    public record Item(Long orderId, String fromStatus, String toStatus, String actorType,
                       String reason, OffsetDateTime createdAt) {
    }

    /** 어뷰징 탐지 재료 — 회원별 전이 횟수 상위 (04 §10) */
    public record MemberCount(Long memberId, long count) {
    }
}
