package com.jarvis.seller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * I-14 주문 상태 전이 로그 (04 §10, 노션 I-14) — 상태 어휘는 우리 상태명(01) 그대로.
 * shape 상호 배제(NON_NULL): 기본 rows(Row)+total, stats=true는 byStatus+cancelReasonsTop(rows 없음),
 * groupBy=memberId는 rows(MemberRow)+total.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SellerOrderEventsResponse(Long brandId, LocalDate from, LocalDate to,
                                        List<?> rows, Integer total, Map<String, Long> byStatus,
                                        List<ReasonCount> cancelReasonsTop) {

    /**
     * orderItemId는 아이템 상태 전이면 값, 주문 상태 전이(PAID 등)면 null이다(2026-08-06 추가).
     * 발송이 아이템 단위라 이게 없으면 같은 orderId의 동일 행이 여러 개로 보여 중복으로 읽힌다.
     */
    public record Row(Long orderId, Long orderItemId, String fromStatus, String toStatus,
                      String actorType, String reason, Long buyerMemberId,
                      OffsetDateTime createdAt) {
    }

    /**
     * 어뷰징 판정 재료 (ANALYSIS_CONFIG.abuse) — isSuspicious = cancelRatio > 0.5 또는
     * maxOrdersPerHour > 10. maxOrdersPerHour는 시간당 distinct 주문 전이 수 근사.
     */
    public record MemberRow(Long buyerMemberId, long orderCount, long cancelCount, double cancelRatio,
                            long maxOrdersPerHour, boolean isSuspicious) {
    }

    public record ReasonCount(String reason, long count) {
    }
}
