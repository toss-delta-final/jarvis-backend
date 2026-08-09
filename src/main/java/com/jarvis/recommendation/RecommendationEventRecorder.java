package com.jarvis.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.global.event.BehaviorEvent;
import com.jarvis.global.event.BehaviorEventPublisher;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * recommendation_generated 서버 적재 (02 D38, 노션 E-1) — 추천 퍼널의 최상단.
 * <p>
 * 서버가 직접 쓰는 이유: E-1은 인증이 없어(permitAll) FastAPI가 그리로 보내면 브라우저 위조와
 * 구별되지 않고, Spring은 콜백을 받는 순간 이미 알고 있어 양쪽이 다 쓰면 이중 계상된다.
 * *generated는 있는데 impression이 없다*면 추천이 눈에 닿지 않았다는 뜻이라 노출 위치 문제를 잡는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecommendationEventRecorder {

    public static final String EVENT_TYPE = "recommendation_generated";

    private final BehaviorEventPublisher behaviorEventPublisher;
    private final ObjectMapper objectMapper;

    /**
     * 목록 1개당 1행 — 세트형이면 목록 수만큼 여러 행(노션 E-1).
     * 실패는 로그만 남긴다: 분석 행 하나 때문에 I-21을 실패시키면 FastAPI가 products.ready를
     * 발행하지 못해 사용자가 카드를 못 받는다. 이미 저장돼 있던 목록(재전송)은 호출자가 걸러 보낸다.
     */
    public void recordGenerated(List<RecommendationList> lists) {
        if (lists.isEmpty()) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            List<BehaviorEvent> events = lists.stream()
                    .map(list -> BehaviorEvent.serverGenerated(EVENT_TYPE, clientEventId(list),
                            list.getMemberId(), list.getGuestId(), sessionKeyOf(list),
                            list.getRecommendationRequestId(), list.getListId(),
                            list.getSurface().name(), properties(list), now))
                    .toList();
            behaviorEventPublisher.publish(events);
        } catch (Exception e) {
            log.warn("recommendation_generated 적재 실패 — {}건 유실", lists.size(), e);
        }
    }

    /**
     * session_key는 NOT NULL인데 이 이벤트에는 FE SDK 세션이 없다 — 채팅 세션 id로 채운다(02 D39).
     * 추천 퍼널의 조인 키는 list_id·recommendation_request_id이므로 분석에 지장은 없다.
     */
    private static String sessionKeyOf(RecommendationList list) {
        return list.getSessionId();
    }

    /**
     * client_event_id도 NOT NULL인데 FE가 보내지 않는다 — listId로 **결정적** UUID를 만든다(02 D40).
     * 목록 하나당 이 이벤트는 정확히 1행이므로, 무작위 대신 결정적 값을 쓰면 UNIQUE 제약이
     * 그대로 재적재 방어선이 된다(호출자의 중복 필터가 뚫려도 DB가 막는다).
     */
    private static String clientEventId(RecommendationList list) {
        return UUID.nameUUIDFromBytes((EVENT_TYPE + ':' + list.getListId())
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String properties(RecommendationList list) {
        return properties(list, null);
    }

    /**
     * @param fallbackReason 홈(P-5)이 인기상품으로 대체된 사유. 와이어에는 싣지 않고 여기에만 남긴다 —
     *                       신규 회원이라 대체된 건지 AI가 죽어서 대체된 건지는 서버만 알면 되고,
     *                       그 구분이 있어야 장애를 관측할 수 있다(노션 P-5)
     */
    private String properties(RecommendationList list, String fallbackReason) {
        try {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("itemCount", list.getItemCount());
            if (fallbackReason != null) {
                properties.put("fallbackReason", fallbackReason);
            }
            return objectMapper.writeValueAsString(properties);
        } catch (Exception e) {
            log.warn("recommendation_generated properties 직렬화 실패 — null로 적재", e);
            return null;
        }
    }

    /**
     * P-5(홈) 1건 적재 — 개인화 성공이든 P-4 대체든 남긴다(노션 I-22 규약). 채팅과 달리 목록이
     * 항상 하나뿐이라 배치 API를 쓰지 않는다.
     *
     * <p>{@code session_key}는 홈에 세션이 없어 {@code listId}를 그대로 쓴다 — 컬럼이 NOT NULL이고,
     * 추천 퍼널의 조인 키는 어차피 {@code list_id}·{@code recommendation_request_id}라 분석에
     *지장이 없다(02 D39와 같은 논리).
     */
    public void recordHomeGenerated(RecommendationList list, String fallbackReason) {
        try {
            LocalDateTime now = LocalDateTime.now();
            behaviorEventPublisher.publish(List.of(BehaviorEvent.serverGenerated(EVENT_TYPE,
                    clientEventId(list), list.getMemberId(), list.getGuestId(), list.getListId(),
                    list.getRecommendationRequestId(), list.getListId(), list.getSurface().name(),
                    properties(list, fallbackReason), now)));
        } catch (Exception e) {
            log.warn("recommendation_generated(홈) 적재 실패 — 1건 유실 (listId={})", list.getListId(), e);
        }
    }
}
