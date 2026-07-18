package com.jarvis.internal.dto;

import com.jarvis.cart.dto.CartResponse;
import java.util.List;

/**
 * I-18 응답 (05 §I-18) — 공개 C-1은 item 필드가 name, 내부 계약은 productName(LLM이 그대로 발화).
 * 같은 CartService 결과를 필드명만 재매핑하며, 합계·부가 필드는 C-1 superset 유지(승인됨).
 */
public record InternalCartResponse(List<Item> items, int totalOriginal, int totalSale, int discount) {

    public record Item(Long cartItemId, Long productId, String productName,
                       Long brandId, String brandName,
                       Long optionId, String optionName,
                       int quantity, int price, int originalPrice,
                       String imageUrl, boolean purchasable) {
    }

    public static InternalCartResponse from(CartResponse cart) {
        List<Item> items = cart.items().stream().map(item -> new Item(
                item.cartItemId(), item.productId(), item.name(),
                item.brandId(), item.brandName(),
                item.optionId(), item.optionName(),
                item.quantity(), item.price(), item.originalPrice(),
                item.imageUrl(), item.purchasable())).toList();
        return new InternalCartResponse(items, cart.totalOriginal(), cart.totalSale(), cart.discount());
    }
}
