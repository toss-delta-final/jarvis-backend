package com.jarvis.product;

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
 * 상품 변경 로그 (02 D32) — append-only, FK 미설정. 전후 값 동일 시 미기록.
 * 주문에 의한 재고 -1은 미기록 — 품절 전환(new_value=0)·수동 조정만 기록.
 */
@Entity
@Table(name = "product_change_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductChangeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false)
    private ProductChangeType changeType;

    @Column(name = "old_value", length = 50)
    private String oldValue;

    @Column(name = "new_value", length = 50)
    private String newValue;

    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    public static ProductChangeLog of(Long productId, ProductChangeType changeType,
                                      String oldValue, String newValue) {
        ProductChangeLog log = new ProductChangeLog();
        log.productId = productId;
        log.changeType = changeType;
        log.oldValue = oldValue;
        log.newValue = newValue;
        log.createdAt = LocalDateTime.now();
        return log;
    }
}
