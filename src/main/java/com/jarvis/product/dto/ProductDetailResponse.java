package com.jarvis.product.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jarvis.brand.Brand;
import com.jarvis.category.Category;
import com.jarvis.global.response.StringId;
import com.jarvis.product.Product;
import com.jarvis.product.PurchaseState;
import com.jarvis.review.dto.RatingStats;
import java.util.List;
import java.util.Map;

/**
 * P-2 (04 §2) — 평점은 집계 파생값(02 D9 — 저장 컬럼 없음, 2026-08-10부터 사본 캐시 + 작성 시 evict).
 *
 * <p>이미지가 둘로 나뉜다: {@code imageUrl}은 상단 대표 1장(02 D14), {@code detailImages}는 하단에
 * 순서대로 나열할 상세 이미지 URL 배열(02 D42). <b>대표는 배열에 포함하지 않는다</b> — 화면에서 역할이
 * 달라서다. 배열 순서가 곧 노출 순서라 {@code {url, order}} 객체로 감싸지 않으며, 상세 이미지가 없는
 * 상품은 {@code null}이 아니라 빈 배열이다.
 */
public record ProductDetailResponse(@StringId Long id, String name, int price, int originalPrice,
                                    int stockQuantity, String purchaseState, String status,
                                    String imageUrl, List<String> detailImages,
                                    String summary, JsonNode attributes,
                                    String description, CategorySummary category,
                                    BrandSummary brand, List<OptionResponse> options,
                                    Rating rating) {

    public record CategorySummary(@StringId Long id, String name) {
        public static CategorySummary from(Category category) {
            return new CategorySummary(category.getId(), category.getName());
        }
    }

    public record BrandSummary(@StringId Long id, String name, String logoUrl) {
        public static BrandSummary from(Brand brand) {
            return new BrandSummary(brand.getId(), brand.getName(), brand.getLogoUrl());
        }
    }

    /**
     * 옵션마다 재고와 구매 가능 여부를 함께 내린다 (2026-08-09, 노션 P-2 개정 · FE 합의).
     *
     * <p><b>검색(I-1)이 품절 옵션을 아예 빼는 것과 의도적으로 반대다</b> — 상세는 사용자가 고르는
     * 화면이라 "원래 없는 옵션"인지 "품절"인지 구분돼야 재입고를 기다릴지 판단할 수 있다.
     * 장바구니·찜이 품절 상품을 숨기지 않고 이유를 표시하는 것과 같은 태도다.
     *
     * <p>{@code purchaseState}는 {@code AVAILABLE}·{@code SOLD_OUT} 둘뿐이다 — 옵션에는 HIDDEN이 없다.
     */
    public record OptionResponse(@StringId Long optionId, String name, int extraPrice,
                                 int stockQuantity, String purchaseState) {

        public static OptionResponse of(ProductDetailFragment.OptionEntry option, int stockQuantity) {
            // 상품 status를 넘기지 않는다 — HIDDEN 상품의 옵션까지 HIDDEN으로 찍으면 "품절인가
            // 내려간 건가"가 옵션 줄에서 또 갈린다. 그 판정은 상품 레벨 purchaseState 하나로 충분하다
            return new OptionResponse(option.optionId(), option.name(), option.extraPrice(),
                    stockQuantity,
                    (stockQuantity > 0 ? PurchaseState.AVAILABLE : PurchaseState.SOLD_OUT).name());
        }
    }

    public record Rating(double average, long count) {
        public static Rating from(RatingStats stats) {
            return new Rating(stats.average(), stats.count());
        }
    }

    /**
     * 살아있는 값(상품 행·재고·평점)과 캐시된 정적 조각을 결합한다 (07 §3-1, 2026-08-10).
     *
     * @param stockQuantity 옵션 재고의 합계 — 저장 컬럼이 아니라 파생값이다 (02 D33 개정).
     *                      옵션 없는 상품은 그 상품의 재고 한 행 값이 그대로 들어온다
     */
    public static ProductDetailResponse of(Product product, JsonNode attributes,
                                           ProductDetailFragment fragment, RatingStats stats,
                                           int stockQuantity, Map<Long, Integer> stockByOption) {
        return new ProductDetailResponse(product.getId(), product.getName(), product.getPrice(),
                product.getOriginalPrice(), stockQuantity,
                PurchaseState.of(product.getStatus(), stockQuantity).name(),
                product.getStatus().name(), product.getImageUrl(), fragment.detailImages(),
                product.getSummary(), attributes, product.getDescription(), fragment.category(),
                fragment.brand(),
                fragment.options().stream()
                        .map(option -> OptionResponse.of(option,
                                stockByOption.getOrDefault(option.optionId(), 0)))
                        .toList(),
                Rating.from(stats));
    }
}
