package com.jarvis.order.dto;

import com.jarvis.product.Product;
import com.jarvis.product.PurchaseState;

/**
 * O-1 400 ORDER_PRODUCT_UNAVAILABLE의 error.detail.unavailableItems 원소 (04 §4, 2026-08-05 FE 요청).
 *
 * <p>FE가 "○○ 상품이 품절됐어요 — 빼고 결제" 복구 동선을 만들려면 어느 상품이 왜 안 되는지가 필요하다.
 * reason 어휘는 목록·상세의 purchaseState와 같다 — 주문 화면과 장바구니가 다른 말을 하면 안 된다.
 */
public record UnavailableItemDetail(Long productId, String name, String reason) {

    /** 구매 가능하면 null. 판정은 PurchaseState 한 곳에서 — 숨김이 품절보다 우선한다 */
    public static UnavailableItemDetail of(Product product, int requestedQuantity) {
        PurchaseState state = PurchaseState.of(product, requestedQuantity);
        return state.isAvailable() ? null
                : new UnavailableItemDetail(product.getId(), product.getName(), state.name());
    }
}
