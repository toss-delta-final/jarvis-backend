package com.jarvis.cart.dto;

import com.jarvis.global.response.StringId;
import com.jarvis.product.PurchaseState;
import java.util.List;

/**
 * C-1 (04 §3) — 합계는 FE 계산이 아니라 서버가 내려줌(totalOriginal/totalSale/discount).
 * HIDDEN·품절 상품은 목록에 유지하되 purchaseState로 이유를 밝히고, 합계에서 제외.
 * 가격은 현재가(장바구니는 스냅샷 아님 — 02 §3).
 *
 * <p>maxQuantity는 수량 스테퍼 상한 — 재고와 담기 상한 99 중 작은 값을 서버가 계산해 내린다
 * (2026-08-05 FE 요청). 재고만 내리면 FE가 99와 다시 비교해야 하는데, 그 99는 서버 규칙이다.
 */
public record CartResponse(List<Item> items, int totalOriginal, int totalSale, int discount) {

    public record Item(@StringId Long cartItemId, @StringId Long productId, String name,
                       @StringId Long brandId, String brandName,
                       @StringId Long optionId, String optionName,
                       int quantity, int price, int originalPrice,
                       String imageUrl, String purchaseState, int maxQuantity) {

        boolean available() {
            return PurchaseState.AVAILABLE.name().equals(purchaseState);
        }
    }

    public static CartResponse of(List<Item> items) {
        int totalOriginal = items.stream().filter(Item::available)
                .mapToInt(i -> i.originalPrice() * i.quantity()).sum();
        int totalSale = items.stream().filter(Item::available)
                .mapToInt(i -> i.price() * i.quantity()).sum();
        return new CartResponse(items, totalOriginal, totalSale, totalOriginal - totalSale);
    }
}
