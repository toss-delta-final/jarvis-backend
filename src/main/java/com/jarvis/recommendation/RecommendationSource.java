package com.jarvis.recommendation;

/**
 * "AI가 뽑았나, 인기상품으로 대신했나"만 뜻한다 (노션 P-5 — 2026-07-30 확정).
 * 지면은 {@link RecommendationSurface}가 맡으므로 여기에 섞지 않는다 —
 * 채팅(I-21)은 항상 AI_RECOMMENDED, 홈(I-22)은 둘 다 가능하다.
 */
public enum RecommendationSource {
    AI_RECOMMENDED,
    POPULAR_FALLBACK
}
