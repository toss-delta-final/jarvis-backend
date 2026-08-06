package com.jarvis.cart.dto;

import com.jarvis.global.response.StringId;
import com.jarvis.product.ProductOption;

/**
 * CART_OPTION_REQUIRED의 error.detail.options 항목 (05 §I-2) — LLM이 "어떤 색상으로?" 되물음용.
 *
 * <p>공개 C-2와 내부 I-2가 같은 CartService를 지나 이 DTO를 공유한다 — FE도 보는 값이라
 * optionId를 문자열로 내보내고, 그 결과 LLM팀도 문자열을 받는다(2026-08-06 통지).
 */
public record CartOptionDetail(@StringId Long optionId, String name, int extraPrice) {

    public static CartOptionDetail from(ProductOption option) {
        return new CartOptionDetail(option.getId(), option.getName(), option.getExtraPrice());
    }
}
