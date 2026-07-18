package com.jarvis.seller.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * I-8 계정 이벤트 집계 (04 §10, 노션 I-8) — 전역(brandId 스코프 아님), IP 마스킹, 집계 전용(raw 미반환).
 * rows 스키마는 groupBy에 따라 갈린다: ip → IpRow, eventType|hour → Bucket(노션에 예시 없음 — {key,count} 유지).
 */
public record AccountEventAggregateResponse(String groupBy, String eventType, LocalDate from,
                                            LocalDate to, List<?> rows) {

    public record Bucket(String key, long count) {
    }

    /**
     * 무차별 대입 신호 재료 — failCount = 해당 IP의 LOGIN_FAIL 수, nullMemberRatio = memberId NULL 행
     * 비율(없는 계정 시도), isSuspicious = LOGIN_FAIL 버스트 판정(ANALYSIS_CONFIG.abuse.login_fail_burst).
     */
    public record IpRow(String ipMasked, long failCount, long distinctMembers, double nullMemberRatio,
                        boolean isSuspicious, OffsetDateTime firstSeen, OffsetDateTime lastSeen) {
    }
}
