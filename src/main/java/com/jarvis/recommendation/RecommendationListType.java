package com.jarvis.recommendation;

/**
 * 목록 안의 상품들이 서로 대체재인지 보완재인지 (노션 I-21 — 2026-07-30 확정).
 * 목록 개수와 조합해 세 화면이 표현된다 — PICK_ONE+1개는 일반 추천, PICK_ONE+N개는 니즈별,
 * BUY_ALL+N개는 세트 여러 안. 판단 기준은 예산이 아니다.
 */
public enum RecommendationListType {
    /** 서로 대안 — 그중 하나만 산다 */
    PICK_ONE,
    /** 각자 다른 역할 — 전부 산다 */
    BUY_ALL
}
