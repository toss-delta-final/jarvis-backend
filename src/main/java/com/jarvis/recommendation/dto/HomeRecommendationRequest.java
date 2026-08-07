package com.jarvis.recommendation.dto;

import java.util.List;

/**
 * I-22 요청 (노션 I-22) — BE → FastAPI. 홈에는 채팅 세션이 없어 {@code sessionId}를 싣지 않는다.
 *
 * <p><b>2026-08-07 개정 반영</b> — {@code catalogVersion}은 폐기됐다(어느 쪽도 의미 있는 값을 만들 수
 * 없다, C-18). {@code limit} 상한 60·{@code signals} 배열 200은 요청 전에 서버가 자른다.
 */
public record HomeRecommendationRequest(Long memberId, int limit, Signals signals) {

    /** 요청 한 번의 노출 목표 개수 상한 (노션 I-22 C-19) — 넘기면 FastAPI가 400을 낸다 */
    public static final int MAX_LIMIT = 60;
    /** 각 시그널 배열 길이 상한 (노션 I-22 C-19) */
    public static final int MAX_SIGNALS = 200;

    /**
     * @param recentlyViewedProductIds <b>최신순</b>(index 0 = 가장 최근). FastAPI가 이 순서로
     *                                 recency decay를 걸므로 뒤집히면 가중치가 조용히 거꾸로 걸린다 —
     *                                 정렬 보장이 Spring 몫이라는 게 2026-08-07 수용한 계약이다
     * @param cartProductIds           현재 장바구니(미결제) — 담기까지 갔다는 건 강한 신호라 가중치가 높다
     * @param recentPurchasedProductIds 최근 주문 — 가중치가 아니라 <b>결과에서 제외하는 필터</b>다
     */
    public record Signals(List<Long> recentlyViewedProductIds, List<Long> cartProductIds,
                          List<Long> recentPurchasedProductIds) {

        public boolean isEmpty() {
            return recentlyViewedProductIds.isEmpty() && cartProductIds.isEmpty()
                    && recentPurchasedProductIds.isEmpty();
        }
    }
}
