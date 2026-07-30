package com.jarvis.global.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * append-only 로그 (02 D31) — created_at만 있는 로그성 테이블이라 BaseTimeEntity 미상속.
 * member_id/product_id는 의도적으로 FK 없음(적재 경량화).
 */
@Entity
@Table(name = "behavior_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BehaviorEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "guest_id", columnDefinition = "char(36)")
    private String guestId;

    @Column(name = "session_key", nullable = false, length = 64)
    private String sessionKey;

    /**
     * 중복 차단 키 — UNIQUE (02 D35). FE 경로는 body의 `id`(필수), 서버 적재는 대상 키로
     * 만든 결정적 UUID(02 D40). NULL이면 UNIQUE가 아무 일도 하지 않아 NOT NULL이다.
     */
    @Column(name = "client_event_id", nullable = false, columnDefinition = "char(36)")
    private String clientEventId;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    @Column(name = "product_id")
    private Long productId;

    @Column(columnDefinition = "json")
    private String properties;

    /**
     * FE 발생 시각 (02 D38) — 서버 수신(created_at)만으로는 SDK 버퍼(10건/5초)만큼 밀려
     * 서버 직접 적재 로그와 순서가 뒤집힌다. 이상치·누락은 서버가 created_at으로 대체하므로
     * 항상 값이 있다(NOT NULL) — 대체한 행은 properties._timeShifted로 구분한다.
     */
    @Column(name = "occurred_at", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime occurredAt;

    /** ↓ 추천 귀속 4종 (02 D38) — 추천에서 비롯된 이벤트에만 채워진다 */
    @Column(name = "recommendation_request_id", columnDefinition = "char(36)")
    private String recommendationRequestId;

    /** FE가 보낸 값을 믿지 않고 서버가 recommendation_list로 도출한다 (E-1 ③.5) */
    @Column(name = "list_id", length = 64)
    private String listId;

    @Column(length = 10)
    private String surface;

    /** 0-based 순위. recommendation_generated는 목록 단위 이벤트라 NULL */
    @Column
    private Integer position;

    /** 서버 수신 시각 — 증분 분석 커서 (02 behavior_events) */
    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    /**
     * FE 경로 (E-1). occurredAt은 이상치 보정을 통과한 값이고(호출자 책임), 추천 귀속은
     * FE가 보낸 값이 아니라 서버가 목록을 조회해 도출한 것이다 (02 D38, 노션 E-1 ③.5).
     */
    public static BehaviorEvent record(Long memberId, String guestId, String sessionKey,
                                       String clientEventId, String eventType, Long productId,
                                       String properties, LocalDateTime occurredAt,
                                       EventAttribution attribution, LocalDateTime receivedAt) {
        BehaviorEvent event = new BehaviorEvent();
        event.memberId = memberId;
        event.guestId = guestId;
        event.sessionKey = sessionKey;
        event.clientEventId = clientEventId;
        event.eventType = eventType;
        event.productId = productId;
        event.properties = properties;
        event.occurredAt = occurredAt;
        event.recommendationRequestId = attribution.recommendationRequestId();
        event.listId = attribution.listId();
        event.surface = attribution.surface();
        event.position = attribution.position();
        event.createdAt = receivedAt;
        return event;
    }

    /**
     * 서버 적재 전용 (02 D38·D39·D40, 노션 E-1) — 추천 퍼널의 최상단.
     * occurred_at은 적재 시점 = created_at이다(브라우저 시계가 개입하지 않으니 이상치 보정 불필요).
     * productId는 목록 단위 이벤트라 NULL — 순위(position)도 없다.
     *
     * @param clientEventId FE가 보내지 않으므로 서버가 만든다 — 대상 키 기반 **결정적** UUID라
     *                      같은 목록을 두 번 적재하면 UNIQUE가 막아준다 (02 D40)
     */
    public static BehaviorEvent serverGenerated(String eventType, String clientEventId,
                                                Long memberId, String guestId,
                                                String sessionKey, String recommendationRequestId,
                                                String listId, String surface, String properties,
                                                LocalDateTime at) {
        BehaviorEvent event = new BehaviorEvent();
        event.eventType = eventType;
        event.clientEventId = clientEventId;
        event.memberId = memberId;
        event.guestId = guestId;
        event.sessionKey = sessionKey;
        event.recommendationRequestId = recommendationRequestId;
        event.listId = listId;
        event.surface = surface;
        event.properties = properties;
        event.occurredAt = at;
        event.createdAt = at;
        return event;
    }
}
