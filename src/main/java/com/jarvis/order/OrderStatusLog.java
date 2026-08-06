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
 *
 * <p>해상도는 "무엇의 상태를 바꿨나"로 가른다 (01 §6.5 규칙 4, 2026-08-06 개정):
 * 아이템 상태 전이는 아이템마다 1행이라 orderItemId가 채워지고, 주문 상태 전이만 null이다.
 * 구 "같은 주문의 동일 전이 배치는 주문 단위 1행" 규칙은 폐기 — 발송이 판매자 행위가 되면서
 * 아이템들이 서로 다른 시각에 다음 단계로 넘어가 "동시 배치"라는 전제가 사라졌다.
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

    /** 아이템 상태 전이면 필수, 주문 상태 전이면 null (02 D32 개정) */
    @Column(name = "order_item_id")
    private Long orderItemId;

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

    /** 주문 상태 전이 — PENDING/PAID/PAYMENT_FAILED/주문 CANCELLED. orderItemId는 null이다 */
    public static OrderStatusLog ofOrder(Long orderId, String fromStatus, String toStatus,
                                         ActorType actorType, String reason) {
        return create(orderId, null, fromStatus, toStatus, actorType, reason);
    }

    /** 아이템 상태 전이 — SHIPPING/DELIVERED/CANCELLED/RETURNED. 아이템마다 1행이다 */
    public static OrderStatusLog ofItem(Long orderId, Long orderItemId, String fromStatus,
                                        String toStatus, ActorType actorType, String reason) {
        return create(orderId, orderItemId, fromStatus, toStatus, actorType, reason);
    }

    private static OrderStatusLog create(Long orderId, Long orderItemId, String fromStatus,
                                         String toStatus, ActorType actorType, String reason) {
        OrderStatusLog log = new OrderStatusLog();
        log.orderId = orderId;
        log.orderItemId = orderItemId;
        log.fromStatus = fromStatus;
        log.toStatus = toStatus;
        log.actorType = actorType;
        log.reason = reason != null && reason.length() > REASON_MAX ? reason.substring(0, REASON_MAX) : reason;
        log.createdAt = LocalDateTime.now();
        return log;
    }
}
