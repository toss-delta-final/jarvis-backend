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
                                  int cohortSize, Double churnRate, PreChurnSignals preChurnSignals,
                                  List<Member> members) {

    /**
     * zeroResultSearchSessions — search 이벤트 properties에 결과 수가 적재되지 않아(E-1 FE 스키마)
     * 계산 불가, 데이터 한계로 0 고정. priceIncreaseExposed = PRICE 인상 이후 해당 상품 조회 근사.
     *
     * <p>{@code returnReasonsTop.count}는 2026-08-06부터 <b>반품 아이템 수</b>다(종전 주문 수) —
     * 로그가 아이템 단위가 된 파생이라 순위는 대체로 유지되나 숫자가 커진다. {@code cancelCount}는
     * {@code COUNT(DISTINCT order_id)}라 불변이다.
     */
    public record PreChurnSignals(long cancelCount, List<ReasonCount> returnReasonsTop,
                                  long zeroResultSearchSessions, long priceIncreaseExposed) {
    }

    public record ReasonCount(String reason, long count) {
    }

    /**
     * preChurnEvent — 클레임 있으면 "RETURNED(상품불량)" 형식, 없으면 마지막 행동 이벤트 타입.
     *
     * <p><b>2026-08-06 개정</b> — ① {@code memberId} → {@code customerLabel}(타입 Long→String,
     * 규약은 {@link com.jarvis.seller.CustomerLabeler}. 같은 브랜드의 I-14와 같은 회원이면 같은
     * 라벨이라 워커 간 대조가 된다) ② {@code lastLoginAt} <b>제거</b> — 마지막 로그인 시각은 계정
     * 보안 정보라 판매자에게 회원 단위로 줄 이유가 없고, 이탈 판정에는 {@code lastActivityAt}으로
     * 충분하다. 이 제거로 {@code account_event_logs} 조인 자체가 사라졌다.
     */
    public record Member(String customerLabel, OffsetDateTime lastActivityAt,
                         long sessions30d, String preChurnEvent) {
    }
}
