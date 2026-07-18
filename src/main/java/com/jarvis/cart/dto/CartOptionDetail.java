package com.jarvis.cart.dto;

import com.jarvis.product.ProductOption;

/** CART_OPTION_REQUIRED의 error.detail.options 항목 (05 §I-2) — LLM이 "어떤 색상으로?" 되물음용 */
public record CartOptionDetail(Long optionId, String name, int extraPrice) {

    public static CartOptionDetail from(ProductOption option) {
        return new CartOptionDetail(option.getId(), option.getName(), option.getExtraPrice());
    }
}
