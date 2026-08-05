package com.jarvis.order.dto;

import com.jarvis.product.Product;
import com.jarvis.product.ProductStatus;

/**
 * O-1 400 ORDER_PRODUCT_UNAVAILABLE의 error.detail.unavailableItems 원소 (04 §4, 2026-08-05 FE 요청).
 *
 * <p>FE가 "○○ 상품이 품절됐어요 — 빼고 결제" 복구 동선을 만들려면 어느 상품이 왜 안 되는지가 필요하다.
 */
public record UnavailableItemDetail(Long productId, String name, String reason) {

    private static final String HIDDEN = "HIDDEN";
    private static final String SOLD_OUT = "SOLD_OUT";

    /**
     * 구매 가능하면 null. 숨김이 품절보다 우선한다 — 숨김은 판매자가 내려 돌아오지 않는 상품이라
     * "재입고되면 알려드릴게요"로 안내하면 안 된다.
     */
    public static UnavailableItemDetail of(Product product, int requestedQuantity) {
        String reason = null;
        if (product.getStatus() != ProductStatus.ON_SALE) {
            reason = HIDDEN;
        } else if (requestedQuantity > product.getStockQuantity()) {
            reason = SOLD_OUT;
        }
        return reason == null ? null
                : new UnavailableItemDetail(product.getId(), product.getName(), reason);
    }
}
