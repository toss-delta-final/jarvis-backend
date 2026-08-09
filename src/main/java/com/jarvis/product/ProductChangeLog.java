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

    /**
     * STOCK 로그에서 어느 옵션인지 (02 D33 개정). 없으면 "재고 100 → 50"이 어느 옵션 얘긴지
     * 판매자가 알 수 없다. PRICE·STATUS는 상품 단위라 null이고, 옵션 없는 상품도 null이다.
     */
    @Column(name = "option_id")
    private Long optionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false)
    private ProductChangeType changeType;

    @Column(name = "old_value", length = 50)
    private String oldValue;

    @Column(name = "new_value", length = 50)
    private String newValue;

    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    /** 상품 단위 변경(PRICE·STATUS) — 옵션 개념이 없다 */
    public static ProductChangeLog of(Long productId, ProductChangeType changeType,
                                      String oldValue, String newValue) {
        return ofOption(productId, null, changeType, oldValue, newValue);
    }

    /** 재고 변경(STOCK) — 옵션 없는 상품이면 optionId가 null이다 */
    public static ProductChangeLog ofOption(Long productId, Long optionId,
                                            ProductChangeType changeType,
                                            String oldValue, String newValue) {
        ProductChangeLog log = new ProductChangeLog();
        log.productId = productId;
        log.optionId = optionId;
        log.changeType = changeType;
        log.oldValue = oldValue;
        log.newValue = newValue;
        log.createdAt = LocalDateTime.now();
        return log;
    }
}
