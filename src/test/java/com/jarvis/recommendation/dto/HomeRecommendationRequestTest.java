package com.jarvis.recommendation.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * I-22 요청 <b>와이어 모양</b> 검증 (노션 I-22 요청 스키마).
 *
 * <p>여기까지 테스트하는 이유: FastAPI의 요청 모델이 {@code extra="forbid"}라 <b>계약에 없는 키가
 * 하나라도 섞이면 본문 전체가 400</b>이다. 그런데 서비스 테스트는 클라이언트를 목으로 대체하므로
 * 직렬화 결과를 아무도 보지 않는다 — 실제로 레코드에 편의 메서드 하나를 뒀다가 Jackson이 그걸
 * 프로퍼티로 실어 보내 홈 추천이 전량 인기상품으로 대체된 적이 있다(2026-08-08).
 */
class HomeRecommendationRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static HomeRecommendationRequest request() {
        return new HomeRecommendationRequest(2L, 12,
                new HomeRecommendationRequest.Signals(List.of(101L, 205L), List.of(7L), List.of()));
    }

    @Test
    @DisplayName("I-22 요청 — 최상위 키는 계약의 셋뿐이다 (FastAPI extra=forbid)")
    void serializesOnlyContractKeys() throws Exception {
        String json = objectMapper.writeValueAsString(request());

        assertThat(objectMapper.readTree(json).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("memberId", "limit", "signals");
    }

    // signals에 편의 메서드를 추가하면 Jackson이 그걸 프로퍼티로 승격시킨다 — isEmpty()가
    // "empty": false 로 나가 400을 맞았다. 레코드 컴포넌트 이름 그대로만 나가야 한다
    @Test
    @DisplayName("I-22 요청 — signals 키는 시그널 3종뿐이다 (편의 메서드가 새어 나오지 않는다)")
    void signalsCarryNoExtraProperty() throws Exception {
        String json = objectMapper.writeValueAsString(request());

        assertThat(objectMapper.readTree(json).get("signals").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("recentlyViewedProductIds", "cartProductIds",
                        "recentPurchasedProductIds");
    }

    // FastAPI는 memberId를 strict int로 받는다 — 문자열로 나가면 400이다(@StringId 오적용 방어)
    @Test
    @DisplayName("I-22 요청 — memberId는 숫자로 나간다 (공개 응답의 id 문자열화 대상이 아니다)")
    void memberIdStaysNumeric() throws Exception {
        String json = objectMapper.writeValueAsString(request());

        assertThat(objectMapper.readTree(json).get("memberId").isNumber()).isTrue();
    }
}
