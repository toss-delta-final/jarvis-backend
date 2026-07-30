package com.jarvis.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.global.event.BehaviorEvent;
import com.jarvis.global.event.BehaviorEventAppender;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

    private final BehaviorEventAppender behaviorEventAppender;
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
                    .map(list -> BehaviorEvent.serverGenerated(EVENT_TYPE,
                            list.getMemberId(), list.getGuestId(), sessionKeyOf(list),
                            list.getRecommendationRequestId(), list.getListId(),
                            list.getSurface().name(), properties(list), now))
                    .toList();
            behaviorEventAppender.append(events);
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

    private String properties(RecommendationList list) {
        try {
            return objectMapper.writeValueAsString(Map.of("itemCount", list.getItemCount()));
        } catch (Exception e) {
            log.warn("recommendation_generated properties 직렬화 실패 — null로 적재", e);
            return null;
        }
    }
}
