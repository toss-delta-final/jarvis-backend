package com.jarvis.order;

import com.jarvis.global.entity.BaseTimeEntity;
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
 * 클레임 신청 레코드 (01 §5) — 아이템당 REQUESTED 1개 제한은 서비스 검증(재신청 고도화 대비 unique 없음).
 * status는 OrderItem.status와 항상 같은 트랜잭션에서 동기 전이.
 */
@Entity
@Table(name = "claim")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Claim extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ClaimType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ClaimStatus status;

    @Column(length = 500)
    private String reason;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    /** 처리 관리자 — MVP 자동 승인은 NULL (01 D10) */
    @Column(name = "processed_by")
    private Long processedBy;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    public static Claim request(Long orderItemId, ClaimType type, String reason) {
        Claim claim = new Claim();
        claim.orderItemId = orderItemId;
        claim.type = type;
        claim.status = ClaimStatus.REQUESTED;
        claim.reason = reason;
        return claim;
    }

    /** 자동 승인 (01 D10) — processed_by는 NULL 유지 */
    void approve(LocalDateTime now) {
        this.status = ClaimStatus.COMPLETED;
        this.processedAt = now;
    }

    /** 승인 시 아이템이 도달할 종결 상태 */
    public OrderItemStatus completedItemStatus() {
        return type == ClaimType.CANCEL ? OrderItemStatus.CANCELLED : OrderItemStatus.RETURNED;
    }

    /** 신청 접수 시 아이템이 있어야 할 상태 (01 §3 매트릭스) */
    public OrderItemStatus requestedItemStatus() {
        return type == ClaimType.CANCEL ? OrderItemStatus.CANCEL_REQUESTED : OrderItemStatus.RETURN_REQUESTED;
    }
}
