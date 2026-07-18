package com.jarvis.seller.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * I-16 이탈 코호트 (04 §10) — 코호트 = 자사 PAID 구매 고객 중 로그인 이력(LOGIN_SUCCESS) 보유 회원.
 * 이탈 = 마지막 로그인이 inactiveDays 이전. preChurnSignals는 마지막 로그인 이전 30일 행동 집계.
 */
public record SellerChurnResponse(Long brandId, int inactiveDays, int customerCount,
                                  int churnedCount, double churnRate, List<ChurnedMember> churned) {

    public record ChurnedMember(Long memberId, OffsetDateTime lastLoginAt, long orderCount,
                                long totalSpent, Signals preChurnSignals) {
    }

    public record Signals(long productViews, long cartAdds, long searches) {
    }
}
