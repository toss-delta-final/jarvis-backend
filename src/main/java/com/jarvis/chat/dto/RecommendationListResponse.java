package com.jarvis.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.List;

/**
 * CH-5 응답 (노션 CH-5 — 2026-07-28 확정) — 순서는 I-21 콜백 저장 순서.
 * 카드에 추천 이유(reason)를 함께 실어 FE가 조인 없이 렌더한다.
 * <p>
 * itemsDropped는 추천 시점 이후 품절·숨김으로 빠진 개수 — 0이어도 항상 내려간다.
 * listType도 항상 존재한다 — FE 렌더 분기의 축("하나 고르기" vs "세트 전체").
 * totalBudget·sum·withinBudget은 BUY_ALL일 때만 존재한다 — PICK_ONE은 하나만 사므로
 * 합계·예산 판정이 무의미하다(NON_NULL 직렬화로 키 자체가 빠진다).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecommendationListResponse(String listId,
                                         String recommendationRequestId,
                                         String listType,
                                         String label,
                                         Integer totalBudget,
                                         Integer sum,
                                         Object withinBudget,
                                         int itemsDropped,
                                         List<RecommendedCardResponse> items) {

    /**
     * BUY_ALL인데 판정 불가(드롭 발생·예산 미발화)면 키 생략이 아니라 null 리터럴을 내려야
     * 한다(노션 CH-5 — 남은 것만으로 예산 안이라고 단정하면 틀리므로 "판정 없음"을 명시).
     * NON_NULL 직렬화에서 자바 null은 키가 사라지므로, null로 직렬화되는 NullNode를 쓴다.
     */
    public static final Object VERDICT_UNAVAILABLE = NullNode.getInstance();
}
