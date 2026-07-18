package com.jarvis.seller.dto;

import java.util.List;

/** S-5/I-11 수정 결과 — changes는 실제 바뀐 필드만 (04 §10 I-11, 05 §1-3 draft field 어휘) */
public record SellerProductUpdateResponse(Long productId, List<Change> changes) {

    public record Change(String field, String before, String after) {
    }
}
