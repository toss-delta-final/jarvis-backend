package com.jarvis.seller.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * I-38 고객 행동 피처 집계 (노션 I-38, 2026-08-10 확정) — AI팀 세그멘테이션(k-means) 입력.
 *
 * <p>회원 노출은 {@code customerLabel}(I-14·I-16과 같은 브랜드 스코프 HMAC)뿐이고 memberId·IP·정밀
 * 시각은 어떤 필드로도 내려가지 않는다. 금액은 원값 대신 구간, 시각은 일 단위 절사다 — 행이 최대
 * {@link #rows}개까지 나가는 API라 재식별 표면을 좁혀둔 것이다.
 *
 * <p><b>⚠️ I-14 {@code groupBy=memberId}의 동명 필드와 뜻이 다르다.</b> I-14의 {@code orderCount}는
 * 기간 내 <b>상태 전이가 있었던</b> 주문이라 취소분을 포함하고({@code cancelCount ⊆ orderCount},
 * 그래서 {@code cancelRatio}가 성립한다), 여기 {@code orderCount}는 <b>결제 완료(PAID) 주문만</b>이라
 * {@code cancelCount}와 서로 배타적이다. 같은 라벨의 두 값이 달라도 회귀가 아니며, 취소율 판단은
 * I-14 쪽 숫자만 쓴다.
 */
public record SellerCustomerFeaturesResponse(LocalDate from, LocalDate to, long totalCustomers,
                                             int rowLimit, boolean truncated,
                                             boolean insufficientCohort,
                                             List<String> amountBuckets, List<Row> rows) {

    /**
     * {@code amountBucket}의 구간 경계 — 응답에 그대로 실어 이 목록이 정본임을 알린다.
     * 소비측이 경계를 상수로 박아두면 구간이 바뀔 때 조용히 어긋나기 때문이다.
     */
    public static final List<String> AMOUNT_BUCKETS =
            List.of("ZERO", "LT_10K", "10K_50K", "50K_100K", "100K_300K", "GTE_300K");

    /**
     * 한 고객의 피처 행.
     *
     * <p>{@code lastActivityDaysAgo}·{@code firstSeenDaysAgo}는 {@code to} 기준 일 단위 절사이고
     * <b>자사 브랜드 상호작용만</b> 본다 — 브랜드 무관 활동을 쓰면 이 판매자에게 <b>타 브랜드에서의
     * 활동 여부</b>가 새어 나간다(I-16이 브랜드 무관인 건 이탈 판정이라는 다른 목적 때문이다).
     * 기간 하한은 두지 않아 {@code firstSeenDaysAgo}는 {@code from} 이전까지 거슬러 간다(가입 기간
     * = tenure 신호), 상한만 {@code to}다.
     */
    public record Row(String customerLabel, long sessions,
                      long productViews, long cartAdds, long checkoutStarts,
                      long orderCount, long cancelCount,
                      String amountBucket,
                      long lastActivityDaysAgo, long firstSeenDaysAgo) {
    }
}
