package com.jarvis.recommendation;

/**
 * 담기·주문에 박아 넣는 추천 출처 (노션 C-2·I-2·O-1) — 검증을 통과한 값만 이 타입이 된다.
 *
 * <p>요청에 실려 온 {@code RecommendationContext}와 모양은 같지만 성격이 다르다. 저 쪽은 클라이언트
 * 주장이고 이 쪽은 서버가 {@code recommendation_list}에서 도출한 사실이다 — {@code recommendationRequestId}도
 * 요청값이 아니라 목록에 저장된 값을 쓴다. 타입을 나눠 둔 건 "검증 전/후"를 컴파일러가 구분하게 하려는 것이다.
 *
 * <p>지면·순위는 담지 않는다 — {@code list_id}로 언제든 조인해 얻을 수 있고, 이벤트(E-1)와 달리
 * 전환은 건수가 적어 비정규화할 이유가 없다.
 */
public record ConversionAttribution(String recommendationRequestId, String listId) {

    /** 추천 경유가 아니거나 검증에 실패한 경우 — 두 컬럼 모두 NULL로 저장된다 */
    public static final ConversionAttribution NONE = new ConversionAttribution(null, null);

    public boolean isPresent() {
        return listId != null;
    }
}
