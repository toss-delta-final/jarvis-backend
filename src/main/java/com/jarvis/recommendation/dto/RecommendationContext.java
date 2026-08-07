package com.jarvis.recommendation.dto;

import jakarta.validation.constraints.Size;

/**
 * 추천 출처 상관키 (노션 C-2·I-2·O-1) — 담기·주문 요청에 선택적으로 실린다.
 *
 * <p>지면(surface)·순위(position)는 담지 않는다. 서버가 {@code listId}로 목록을 조회해 도출하므로
 * 클라이언트가 보낼 이유가 없고, 보내게 하면 조작 가능한 값이 하나 늘 뿐이다(E-1 ③.5와 같은 원칙).
 *
 * <p>길이 상한은 저장 컬럼과 맞춘다 — 초과 값은 검증 단계에서 어차피 목록을 못 찾아 폐기되지만,
 * DB까지 들고 가서 잘리는 것보다 요청 경계에서 거르는 편이 낫다.
 */
public record RecommendationContext(
        @Size(max = 36) String recommendationRequestId,
        @Size(max = 64) String listId) {
}
