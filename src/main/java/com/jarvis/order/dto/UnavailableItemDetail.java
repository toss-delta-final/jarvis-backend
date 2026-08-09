package com.jarvis.order.dto;

import com.jarvis.global.response.StringId;
import com.jarvis.product.Product;
import com.jarvis.product.PurchaseState;

/**
 * O-1 400 ORDER_PRODUCT_UNAVAILABLE의 error.detail.unavailableItems 원소 (04 §4, 2026-08-05 FE 요청).
 *
 * <p>FE가 "○○ 상품이 품절됐어요 — 빼고 결제" 복구 동선을 만들려면 어느 상품이 왜 안 되는지가 필요하다.
 * reason 어휘는 목록·상세의 purchaseState와 같다 — 주문 화면과 장바구니가 다른 말을 하면 안 된다.
 *
 * <p><b>2026-08-09 — 옵션을 함께 싣는다</b>(02 D33 개정). 재고가 옵션별이라 같은 상품의 한 옵션만
 * 품절일 수 있고, 그러면 상품 id만으로는 FE가 어느 줄을 빼야 할지 알 수 없다.
 */
public record UnavailableItemDetail(@StringId Long productId, String name,
                                    @StringId Long optionId, String optionName, String reason) {

    /**
     * 구매 가능하면 null. 판정은 PurchaseState 한 곳에서 — 숨김이 품절보다 우선한다.
     *
     * @param availableQuantity 그 옵션의 남은 재고 (옵션 없는 상품이면 상품의 유일한 재고 행)
     */
    public static UnavailableItemDetail of(Product product, Long optionId, String optionName,
                                           int availableQuantity, int requestedQuantity) {
        PurchaseState state = PurchaseState.of(product.getStatus(), availableQuantity, requestedQuantity);
        return state.isAvailable() ? null
                : new UnavailableItemDetail(product.getId(), product.getName(),
                        optionId, optionName, state.name());
    }
}
