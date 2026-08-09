package com.jarvis.product.dto;

import java.util.List;

/**
 * P-2 상세의 정적 조각 — Redis 캐시 값 (07 §3-1, 2026-08-10).
 *
 * <p><b>재고·가격·판매상태는 여기 없다</b> — 틀리면 안 되는 축은 매 요청 DB에서 온다.
 * 여기 담긴 것(카테고리·브랜드 요약, 옵션의 정체, 상세 이미지 URL)은 판매자 수정으로만 바뀌고,
 * 그 경로가 evict를 걸므로 TTL(1시간)은 안전망이다.
 */
public record ProductDetailFragment(ProductDetailResponse.CategorySummary category,
                                    ProductDetailResponse.BrandSummary brand,
                                    List<OptionEntry> options,
                                    List<String> detailImages) {

    /** 옵션의 정체(이름·추가금)만 — 재고·구매가능 여부는 응답 조립 시 살아있는 값과 결합한다 */
    public record OptionEntry(Long optionId, String name, int extraPrice) {
    }
}
