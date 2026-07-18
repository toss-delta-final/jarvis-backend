package com.jarvis.seller.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * I-16 이탈 코호트 (노션 I-16) — 코호트 = from~to에 자사 상품과 상호작용(behavior_events)한 회원,
 * 이탈 = 최근 inactiveDays일 behavior_events 무활동. churnRate는 소수(fraction).
 * preChurnSignals는 이탈 회원 전체 기준 집계, members는 이탈 회원 상세(캡 적용).
 */
public record SellerChurnResponse(Long brandId, LocalDate from, LocalDate to, int inactiveDays,
                                  int cohortSize, double churnRate, PreChurnSignals preChurnSignals,
                                  List<Member> members) {

    /**
     * zeroResultSearchSessions — search 이벤트 properties에 결과 수가 적재되지 않아(E-1 FE 스키마)
     * 계산 불가, 데이터 한계로 0 고정. priceIncreaseExposed = PRICE 인상 이후 해당 상품 조회 근사.
     */
    public record PreChurnSignals(long cancelCount, List<ReasonCount> returnReasonsTop,
                                  long zeroResultSearchSessions, long priceIncreaseExposed) {
    }

    public record ReasonCount(String reason, long count) {
    }

    /** preChurnEvent — 클레임 있으면 "RETURNED(상품불량)" 형식, 없으면 마지막 행동 이벤트 타입 */
    public record Member(Long memberId, OffsetDateTime lastActivityAt, OffsetDateTime lastLoginAt,
                         long sessions30d, String preChurnEvent) {
    }
}
