package com.jarvis.global.event;

import java.time.LocalDateTime;

/**
 * behavior-events 토픽의 와이어 계약 (08 D1·D2). 엔티티를 그대로 싣지 않는 이유는 응답 DTO와
 * 같다 — 토픽은 프로듀서·컨슈머가 공유하는 계약이라 JPA 매핑 변경이 곧 계약 변경이 되면 안 된다.
 *
 * <p>필드는 {@code behavior_events} 컬럼과 1:1이다. 검증·신원 주입·추천 귀속 도출(E-1 ①~③.5)은
 * <b>발행 전에</b> 끝나 있으므로, 컨슈머는 판단하지 않고 적재만 한다.
 */
public record BehaviorEventMessage(
        Long memberId,
        String guestId,
        String sessionKey,
        String clientEventId,
        String eventType,
        Long productId,
        String properties,
        LocalDateTime occurredAt,
        String recommendationRequestId,
        String listId,
        String surface,
        Integer position,
        LocalDateTime createdAt) {

    public static BehaviorEventMessage from(BehaviorEvent event) {
        return new BehaviorEventMessage(
                event.getMemberId(), event.getGuestId(), event.getSessionKey(),
                event.getClientEventId(), event.getEventType(), event.getProductId(),
                event.getProperties(), event.getOccurredAt(),
                event.getRecommendationRequestId(), event.getListId(), event.getSurface(),
                event.getPosition(), event.getCreatedAt());
    }

    public BehaviorEvent toEntity() {
        return BehaviorEvent.record(memberId, guestId, sessionKey, clientEventId, eventType,
                productId, properties, occurredAt,
                new EventAttribution(recommendationRequestId, listId, surface, position),
                createdAt);
    }

    /**
     * 파티션 키 (08 D1) — 같은 세션의 이벤트는 한 파티션에 순서대로 쌓인다. 세션 수가 많아
     * 쏠림이 없고, 서로 다른 세션 사이의 순서는 어차피 쓰이지 않는다.
     */
    public String partitionKey() {
        return sessionKey;
    }
}
