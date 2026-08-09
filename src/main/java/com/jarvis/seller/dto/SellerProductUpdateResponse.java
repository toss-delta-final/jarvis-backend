package com.jarvis.seller.dto;

import java.util.List;

/**
 * I-11 수정 결과 (노션 I-11) — 수정 후 현재값 echo,
 * changes는 change log 어휘 대문자 배열(PRICE/STOCK/STATUS만 — 로그 없는 필드 변경은 미포함).
 */
public record SellerProductUpdateResponse(Long productId, int price, int stockQuantity,
                                          List<StockView> stocks,
                                          String status, List<String> changes) {

    /**
     * {@code stockQuantity}는 옵션 재고의 <b>합계 파생값</b>이다 — I-9와 같은 기준.
     * {@code stocks}로 갈아치우지 않고 남긴 이유는 소비측 파서가 이 필드에 기본값 0을 두고 있어서다.
     * 없애면 에러 없이 "수정했더니 재고가 0이 됐다"로 읽힌다(2026-08-09 AI팀이 I-9에서 짚은 것과 같은 함정).
     */
    public static SellerProductUpdateResponse of(Long productId, int price, List<StockView> stocks,
                                                 String status, List<String> changes) {
        int total = stocks.stream().mapToInt(StockView::quantity).sum();
        return new SellerProductUpdateResponse(productId, price, total, stocks, status, changes);
    }
}
