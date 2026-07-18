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

    /** FE 생성 UUID — UNIQUE로 중복 차단 (02 D35) */
    @Column(name = "client_event_id", columnDefinition = "char(36)")
    private String clientEventId;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    @Column(name = "product_id")
    private Long productId;

    @Column(columnDefinition = "json")
    private String properties;

    /** 서버 수신 시각 — 증분 분석 커서 (02 behavior_events) */
    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    public static BehaviorEvent record(Long memberId, String guestId, String sessionKey,
                                       String clientEventId, String eventType, Long productId,
                                       String properties, LocalDateTime receivedAt) {
        BehaviorEvent event = new BehaviorEvent();
        event.memberId = memberId;
        event.guestId = guestId;
        event.sessionKey = sessionKey;
        event.clientEventId = clientEventId;
        event.eventType = eventType;
        event.productId = productId;
        event.properties = properties;
        event.createdAt = receivedAt;
        return event;
    }
}
