package com.jarvis.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 상태 전이 로그 (01 D12·§6.5, 02 D32) — append-only, FK 미설정.
 * 기록은 OrderStatusChanger에서 상태 UPDATE와 같은 트랜잭션으로만 — 우회 INSERT 금지.
 * from/to는 주문 수준·아이템 이행 수준 어휘가 섞이므로 문자열 컬럼.
 */
@Entity
@Table(name = "order_status_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderStatusLog {

    private static final int REASON_MAX = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "from_status", length = 20)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 20)
    private String toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false)
    private ActorType actorType;

    @Column(length = 200)
    private String reason;

    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    public static OrderStatusLog of(Long orderId, String fromStatus, String toStatus,
                                    ActorType actorType, String reason) {
        OrderStatusLog log = new OrderStatusLog();
        log.orderId = orderId;
        log.fromStatus = fromStatus;
        log.toStatus = toStatus;
        log.actorType = actorType;
        log.reason = reason != null && reason.length() > REASON_MAX ? reason.substring(0, REASON_MAX) : reason;
        log.createdAt = LocalDateTime.now();
        return log;
    }
}
