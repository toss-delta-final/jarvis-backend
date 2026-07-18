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
 * 주문 상품 스냅샷 (02 D1·D37) — 가격/이름은 주문 시점 값 보존, product_id는 상세 링크용.
 * status 전이는 OrderStatusChanger의 조건부 UPDATE 경유 (01 D12·§6).
 */
@Entity
@Table(name = "order_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "option_name", length = 100)
    private String optionName;

    /** 스냅샷: product.price + extra_price */
    @Column(nullable = false)
    private int price;

    /** 스냅샷: product.original_price + extra_price — 할인 표시용 (02 D37) */
    @Column(name = "original_price", nullable = false)
    private int originalPrice;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderItemStatus status;

    @Column(name = "status_changed_at", nullable = false)
    private LocalDateTime statusChangedAt;

    /** 주문과 함께 PENDING으로 생성 — 결제 성공 시 ORDERED 전이 (01 D9) */
    public static OrderItem pending(Long orderId, Long productId, String productName, String optionName,
                                    int price, int originalPrice, int quantity, LocalDateTime now) {
        OrderItem item = new OrderItem();
        item.orderId = orderId;
        item.productId = productId;
        item.productName = productName;
        item.optionName = optionName;
        item.price = price;
        item.originalPrice = originalPrice;
        item.quantity = quantity;
        item.status = OrderItemStatus.PENDING;
        item.statusChangedAt = now;
        return item;
    }

    /** 결제 성공 — Order PAID와 같은 트랜잭션에서만 호출 (01 §2-3) */
    void markOrdered(LocalDateTime now) {
        this.status = OrderItemStatus.ORDERED;
        this.statusChangedAt = now;
    }
}
