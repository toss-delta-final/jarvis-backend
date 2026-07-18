package com.jarvis.cart.dto;

import java.util.List;

/**
 * C-1 (04 §3) — 합계는 FE 계산이 아니라 서버가 내려줌(totalOriginal/totalSale/discount).
 * HIDDEN·품절 상품은 목록에 유지하되 purchasable=false, 합계에서 제외.
 * 가격은 현재가(장바구니는 스냅샷 아님 — 02 §3).
 */
public record CartResponse(List<Item> items, int totalOriginal, int totalSale, int discount) {

    public record Item(Long cartItemId, Long productId, String productName,
                       Long brandId, String brandName,
                       Long optionId, String optionName,
                       int quantity, int price, int originalPrice,
                       String imageUrl, boolean purchasable) {
    }

    public static CartResponse of(List<Item> items) {
        int totalOriginal = items.stream().filter(Item::purchasable)
                .mapToInt(i -> i.originalPrice() * i.quantity()).sum();
        int totalSale = items.stream().filter(Item::purchasable)
                .mapToInt(i -> i.price() * i.quantity()).sum();
        return new CartResponse(items, totalOriginal, totalSale, totalOriginal - totalSale);
    }
}
