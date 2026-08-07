package com.jarvis.recommendation.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * I-22 응답 (노션 I-22) — FastAPI → BE.
 *
 * <p><b>cold start는 오류가 아니다</b> — 프로필이 없어도 200 + {@code outcome}으로 답한다.
 * 4xx/5xx는 토큰 오류·상류 장애·타임아웃일 때만 나오며, 어느 쪽이든 Spring이 P-4로 대체한다.
 *
 * <p>{@code @JsonIgnoreProperties} — FastAPI가 필드를 더 붙여도 우리 파싱이 깨지지 않게 한다.
 * 계약상 우리가 읽는 건 아래 넷뿐이다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HomeRecommendationResponse(String outcome, String recommendationRequestId,
                                         String listId, List<Item> items) {

    public static final String PERSONALIZED = "PERSONALIZED";

    /** 배열 순서가 곧 순위다 — {@code position}을 따로 싣지 않는다 (노션 I-22) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(Long productId, String reason) {
    }

    /**
     * 개인화 성공으로 쓸 수 있는 응답인지. {@code PERSONALIZED}가 아니거나 상관키·목록이 비면
     * 대체한다 — 상관키가 없으면 FE 노출·클릭 이벤트가 귀속될 곳을 잃는다.
     */
    public boolean isUsable() {
        return PERSONALIZED.equals(outcome)
                && recommendationRequestId != null && !recommendationRequestId.isBlank()
                && listId != null && !listId.isBlank()
                && items != null && !items.isEmpty();
    }
}
