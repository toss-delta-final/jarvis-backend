package com.jarvis.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.recommendation.HomeRecommendationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * AI 취향 프로필 (노션 M-11~M-16) — I-32~I-37 프록시.
 *
 * <p>Spring이 하는 일은 두 가지뿐이다: <b>회원 id를 로그인 세션에서만 도출</b>하는 것과,
 * 취향이 바뀌면 <b>P-5 홈 캐시를 버리는 것</b>(C-27). 본문은 해석하지 않는다.
 *
 * <p>캐시 무효화는 <b>성공한 뒤에만</b> 한다 — 실패했으면 AI 쪽 상태가 그대로라 버릴 이유가 없고,
 * 괜히 버리면 다음 홈 요청이 이유 없이 I-22를 한 번 더 태운다.
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final ProfileGraphClient profileGraphClient;
    private final HomeRecommendationService homeRecommendationService;

    /** M-11 — 읽기라 캐시를 건드리지 않는다 */
    public JsonNode getGraph(long memberId) {
        return profileGraphClient.getGraph(memberId);
    }

    /** M-12 — 고치기 전 취향으로 만든 추천이 홈에 남지 않게 캐시를 버린다 */
    public JsonNode updateEdge(long memberId, String edgeId, JsonNode body, String ifMatch) {
        requireIfMatch(ifMatch);
        JsonNode data = profileGraphClient.updateEdge(memberId, edgeId, body, ifMatch);
        homeRecommendationService.evictCache(memberId);
        return data;
    }

    /** M-13 — 되돌릴 수 없는 삭제다. 지운 취향이 홈에 남으면 "지웠다"는 말이 거짓이 된다 */
    public JsonNode deleteEdge(long memberId, String edgeId, String ifMatch) {
        requireIfMatch(ifMatch);
        JsonNode data = profileGraphClient.deleteEdge(memberId, edgeId, ifMatch);
        homeRecommendationService.evictCache(memberId);
        return data;
    }

    /** M-15 — 전부 지웠는데 홈만 그대로면 가장 나쁘다 */
    public JsonNode reset(long memberId, JsonNode body, String ifMatch) {
        requireIfMatch(ifMatch);
        JsonNode data = profileGraphClient.reset(memberId, body, ifMatch);
        homeRecommendationService.evictCache(memberId);
        return data;
    }

    /**
     * M-16 — 껐는데 개인화 홈이 남아 있으면 스위치가 거짓말이 된다. 본문을 해석하지 않으므로
     * 끄기·켜기를 구분하지 않고 버린다 — 켜기일 때는 중지 중 대체분(인기상품)이 애초에 캐시되지
     * 않으므로 버릴 게 없어 무해하다.
     */
    public JsonNode setPersonalization(long memberId, JsonNode body, String ifMatch) {
        if (ifMatch != null && !ifMatch.isBlank()) {
            requireWellFormed(ifMatch);
        }
        JsonNode data = profileGraphClient.setPersonalization(memberId, body, ifMatch);
        homeRecommendationService.evictCache(memberId);
        return data;
    }

    /**
     * M-12·M-13·M-15는 {@code If-Match} <b>필수</b>다 — 누락은 실행하지 않고 400이다.
     * 되돌릴 수 없는 동작일수록 "그 상태에서 하는 게 맞는지" 확인할 값어치가 크다.
     */
    private static void requireIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        requireWellFormed(ifMatch);
    }

    /**
     * {@code *}와 약한 태그({@code W/"g42"})는 400 — "정확히 이 버전"이라는 보장을 무너뜨린다
     * (428은 도입하지 않는다). <b>값 자체는 손대지 않는다</b>: {@code "g42"}와 {@code g42}는
     * 동등하고, 재따옴표·정규화는 금지다(C-21). 여기서는 거르기만 하고 변형하지 않는다.
     */
    private static void requireWellFormed(String ifMatch) {
        String value = ifMatch.trim();
        if ("*".equals(value) || value.startsWith("W/")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }
}
