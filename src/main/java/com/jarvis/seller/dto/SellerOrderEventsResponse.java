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
     *
     * <p>{@code customerLabel}은 구 {@code buyerMemberId}를 대체한다(2026-08-06 프라이버시 개정,
     * 타입 Long→String) — {@code orderId}+memberId를 S-2의 수령인 실명과 대조하면 재식별되기 때문이다.
     * 생성 규칙은 {@link com.jarvis.seller.CustomerLabeler}. 라벨이 곧 사례번호다.
     */
    public record Row(Long orderId, Long orderItemId, String fromStatus, String toStatus,
                      String actorType, String reason, String customerLabel,
                      OffsetDateTime createdAt) {
    }

    /**
     * 어뷰징 판정 재료 (ANALYSIS_CONFIG.abuse) — isSuspicious = cancelRatio > 0.5 또는
     * maxOrdersPerHour > 10. maxOrdersPerHour는 시간당 distinct 주문 전이 수 근사.
     *
     * <p>{@code isSuspicious}는 여기선 <b>유지</b>한다 — I-14는 원래 브랜드 스코프라 임계가 정상
     * 작동한다(전역→코호트 전환으로 항상 false가 돼 제거된 I-8과 다르다, 노션 2026-08-06).
     * {@code groupBy=memberId} 파라미터 이름도 유지한다 — 그룹핑 축은 여전히 회원이고 노출만 라벨이다.
     */
    public record MemberRow(String customerLabel, long orderCount, long cancelCount, double cancelRatio,
                            long maxOrdersPerHour, boolean isSuspicious) {
    }

    public record ReasonCount(String reason, long count) {
    }
}
