package com.jarvis.product;

import com.jarvis.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 재고 (02 D33 개정 — 구 {@code product.stock_quantity}). 옵션마다 한 행,
 * 옵션 없는 상품은 {@code optionId}가 {@code null}인 한 행.
 *
 * <p><b>재고가 사는 곳은 여기 하나뿐이다.</b> 상품에 합계를 같이 두지 않는 건 같은 사실이 두 곳에
 * 생기면 어긋나도 에러가 나지 않기 때문이다(평점에 대해 D9가 내린 판단과 같다). 합계가 필요한
 * 화면은 조회 시 집계한다.
 *
 * <p>차감은 결제 성공 트랜잭션의 조건부 UPDATE — {@link ProductStockService}가 유일한 관문이다.
 */
@Entity
@Table(name = "product_stock")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductStock extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    /** null이면 옵션 없는 상품의 유일한 재고 행 */
    @Column(name = "option_id")
    private Long optionId;

    @Column(nullable = false)
    private int quantity;

    public static ProductStock of(Long productId, Long optionId, int quantity) {
        ProductStock stock = new ProductStock();
        stock.productId = productId;
        stock.optionId = optionId;
        stock.quantity = quantity;
        return stock;
    }

    /** 판매자 재고 수정(I-11) — 주문 차감은 이 경로가 아니라 조건부 UPDATE다 */
    public void changeQuantity(int quantity) {
        this.quantity = quantity;
    }

    public boolean isSoldOut() {
        return quantity <= 0;
    }
}
