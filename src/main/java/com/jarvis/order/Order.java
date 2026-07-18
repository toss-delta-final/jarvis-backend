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
import java.time.format.DateTimeFormatter;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 스냅샷 (02 D1) — 배송지는 address FK가 아니라 값 복사(주소 수정·삭제돼도 주문 보존).
 * 상태 전이는 반드시 OrderStatusChanger 경유 (01 D12 — 우회 UPDATE 금지).
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseTimeEntity {

    private static final DateTimeFormatter ORDER_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "payment_method", nullable = false, length = 30)
    private String paymentMethod;

    /** 항상 Σ(order_item.price × quantity) — 서버 계산으로만 기록 (02 D26④), 배송비 항 없음 (02 D36) */
    @Column(name = "total_amount", nullable = false)
    private int totalAmount;

    @Column(nullable = false, length = 50)
    private String recipient;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(name = "zip_code", nullable = false, length = 10)
    private String zipCode;

    @Column(nullable = false, length = 255)
    private String address1;

    @Column(length = 255)
    private String address2;

    @Column(name = "delivery_request", length = 200)
    private String deliveryRequest;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    public static Order create(Long memberId, String paymentMethod, int totalAmount,
                               String recipient, String phone, String zipCode,
                               String address1, String address2, String deliveryRequest) {
        Order order = new Order();
        order.memberId = memberId;
        order.status = OrderStatus.PENDING;
        order.paymentMethod = paymentMethod;
        order.totalAmount = totalAmount;
        order.recipient = recipient;
        order.phone = phone;
        order.zipCode = zipCode;
        order.address1 = address1;
        order.address2 = address2;
        order.deliveryRequest = deliveryRequest;
        return order;
    }

    void markPaid(LocalDateTime now) {
        this.status = OrderStatus.PAID;
        this.paidAt = now;
    }

    void markPaymentFailed() {
        this.status = OrderStatus.PAYMENT_FAILED;
    }

    /** 전량 취소 승격 (01 §2-1) — 마지막 아이템 CANCELLED와 같은 트랜잭션 */
    void markCancelled() {
        this.status = OrderStatus.CANCELLED;
    }

    /** O-2 재결제 — 실패 주문에서 결제 수단 교체 허용 */
    public void changePaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public boolean isRetryable() {
        return status == OrderStatus.PENDING || status == OrderStatus.PAYMENT_FAILED;
    }

    /** 표시용 주문번호 — 저장하지 않고 파생 (02 D24) */
    public String orderNo() {
        return orderNo(id, getCreatedAt());
    }

    public static String orderNo(Long id, LocalDateTime createdAt) {
        return "ORD-" + createdAt.format(ORDER_NO_DATE) + "-" + id;
    }
}
