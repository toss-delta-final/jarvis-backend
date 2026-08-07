package com.jarvis.product.dto;

import com.jarvis.global.response.StringId;
import java.util.List;

/**
 * P-5 개인화 추천 응답 (노션 P-5) — 카드 필드는 P-4와 같고 <b>여기에 추천 상관키 3종과 카드별
 * {@code reason}이 붙는다</b>. "P-4와 동형"이라고만 적어두면 상관키가 통째로 빠지므로 주의.
 *
 * <p>{@code source}가 표시 분기의 축이다 — {@code NOT_PERSONALIZED}면 FE는 <b>추천 컴포넌트 자체를
 * 그리지 않는다</b>(2026-08-07 개정). 홈에 인기 컴포넌트가 따로 있고 그건 P-4를 직접 부르므로,
 * 대체분을 추천 자리에 그리면 같은 상품이 화면에 두 번 나온다.
 *
 * <p><b>이 {@code source}는 DB의 {@code recommendation_list.source}와 어휘가 다르다.</b>
 * 응답은 FE가 묻는 <i>"개인화됐나"</i>에 답하고({@code PERSONALIZED}/{@code NOT_PERSONALIZED}),
 * DB는 <i>"이 목록이 어디서 왔나"</i>를 기록한다({@code AI_RECOMMENDED}/{@code POPULAR_FALLBACK}).
 * 다른 질문이라 어휘가 갈리는 게 맞다 — 특히 대체 사유는 넷인데(프로필 없음·후보 부족·AI 에러·
 * 타임아웃) FE 동작은 하나뿐이라, 원인을 주장하는 이름({@code NO_PROFILE} 같은)을 쓰면 나머지
 * 셋에서 거짓말이 된다. DB 값은 AI 추천 성과 집계가 의존하므로 건드리지 않는다.
 *
 * <p><b>fallback에도 상관키가 실린다.</b> 목록을 Spring이 만들었으므로 Spring이 발급한다 —
 * 없으면 FE가 인기상품 카드에 대해 쏘는 노출·클릭 이벤트가 <b>부모 없는 고아</b>가 되어
 * E-1의 서버 검증에서 버려진다.
 *
 * <p>와이어에 싣지 않는 것: {@code fallbackReason}·{@code cacheStatus}·{@code algorithmVersion}
 * — FE 동작이 달라지지 않는 값이다. {@code fallbackReason}은 {@code recommendation_generated}
 * 이벤트에만 저장해 장애 관측에 쓴다.
 */
public record RecommendedProductsResponse(String source, String recommendationRequestId,
                                          String listId, List<Item> items) {

    /** 개인화 성공 — FE가 추천 컴포넌트를 그린다 */
    public static final String PERSONALIZED = "PERSONALIZED";
    /** 개인화 불가(원인 무관) — FE가 추천 컴포넌트를 숨긴다 */
    public static final String NOT_PERSONALIZED = "NOT_PERSONALIZED";

    /** {@code reason}은 {@code POPULAR_FALLBACK}일 때 null이다 — 키는 유지한다(CH-5와 동일 규칙) */
    public record Item(@StringId Long productId, String name, String brandName,
                       int price, int originalPrice, String imageUrl,
                       double rating, long reviewCount, String reason) {

        public static Item of(ProductCardResponse card, String reason) {
            return new Item(card.productId(), card.name(), card.brandName(),
                    card.price(), card.originalPrice(), card.imageUrl(),
                    card.rating(), card.reviewCount(), reason);
        }
    }
}
