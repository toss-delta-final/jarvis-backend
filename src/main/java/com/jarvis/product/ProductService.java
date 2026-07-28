package com.jarvis.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.brand.BrandService;
import com.jarvis.category.CategoryService;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.product.dto.CandidateRow;
import com.jarvis.product.dto.PopularCardResponse;
import com.jarvis.product.dto.ProductCandidateResponse;
import com.jarvis.product.dto.ProductCardPageResponse;
import com.jarvis.product.dto.ProductCardResponse;
import com.jarvis.product.dto.ProductDetailResponse;
import com.jarvis.product.dto.ProductChangesResponse;
import com.jarvis.review.ReviewService;
import com.jarvis.review.dto.RatingStats;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private static final int POPULAR_DAYS = 7;
    private static final int SYNC_DEFAULT_LIMIT = 500; // I-17 기본 페이지 크기 (05 §I-17)
    private static final int SYNC_MAX_LIMIT = 500; // I-17 페이지 상한 (05 §I-17)
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul"); // 응답 타임스탬프 관례 (I-19와 동일)

    private final ProductRepository productRepository;
    private final ProductOptionRepository productOptionRepository;
    private final BrandService brandService;
    private final CategoryService categoryService;
    private final ReviewService reviewService;
    private final ObjectMapper objectMapper;

    /**
     * P-2 — HIDDEN도 응답한다(purchasable=false): 장바구니가 HIDDEN 아이템을 유지(C-1)하므로
     * 상세 링크가 404가 되면 안 됨. 목록(P-4/P-6/CH-5)에서는 제외.
     */
    public ProductDetailResponse getDetail(Long id) {
        Product product = getProduct(id);
        return ProductDetailResponse.from(product, parseJson(product.getAttributes()),
                categoryService.getCategory(product.getCategoryId()),
                brandService.getBrand(product.getBrandId()),
                productOptionRepository.findAllByProductIdOrderByIdAsc(id),
                reviewService.getStats(id));
    }

    /** P-4 — 7일 판매수 → product_view 수 → 최신순 순으로 채움 (04 §2). 품절은 집계 단계에서 이미 빠진다 */
    public List<PopularCardResponse> getPopular(int size) {
        return toCards(findByIdsPreservingOrder(popularIds(size))).stream()
                .map(PopularCardResponse::of)
                .toList();
    }

    /**
     * I-3 — 인기 상품을 후보 형식으로 (05 §I-3 — 응답 형식 I-1과 동일). 상위 N건 고정이라
     * 평점은 배치 집계로 충분하다 — I-1과 달리 후보 수가 유한하다.
     */
    public List<ProductCandidateResponse> getPopularCandidates(int size) {
        List<Product> products = findByIdsPreservingOrder(popularIds(size));
        Map<Long, RatingStats> stats = reviewService.getStats(
                products.stream().map(Product::getId).toList());
        return toCandidates(products.stream().map(p -> {
            RatingStats s = stats.getOrDefault(p.getId(), RatingStats.EMPTY);
            return new CandidateRow(p, s.count(), s.average());
        }).toList());
    }

    /**
     * I-1 라운드1 후보 조회 (05 §I-1) — 정형조건만 SQL 적용. 2026-07-27 개정: 후보 수 상한 폐지,
     * `brandName`은 리스트(하나라도 일치하면 후보). 미존재 카테고리명, 그리고 브랜드명이 전부
     * 미존재면 후보 0건(잘못된 축으로 전체가 매칭되는 것 방지) — 일부만 미존재면 나머지로 검색해
     * 브랜드 하나 잘못 넣었다고 추천 전체가 죽지 않게 한다.
     */
    public List<ProductCandidateResponse> searchCandidates(String keyword, String categoryName,
                                                           Integer minPrice, Integer maxPrice,
                                                           List<String> brandNames, String color) {
        List<Long> categoryIds = null;
        if (hasText(categoryName)) {
            categoryIds = categoryService.resolveIdsByName(categoryName.trim()).orElse(List.of());
            if (categoryIds.isEmpty()) {
                return List.of();
            }
        }
        List<String> names = brandNames == null ? List.of()
                : brandNames.stream().filter(ProductService::hasText).map(String::trim).distinct().toList();
        List<Long> brandIds = null;
        if (!names.isEmpty()) {
            brandIds = names.stream().map(brandService::findIdByName)
                    .flatMap(Optional::stream).distinct().toList();
            if (brandIds.isEmpty()) {
                return List.of();
            }
        }
        return toCandidates(productRepository.searchCandidates(
                trimToNull(keyword),
                categoryIds != null, categoryIds != null ? categoryIds : List.of(-1L),
                brandIds != null, brandIds != null ? brandIds : List.of(-1L),
                minPrice, maxPrice, trimToNull(color)));
    }

    /**
     * I-17 상품 변경분 배치 pull (05 §I-17) — (updatedAt, id) keyset 커서. since="0"이면 처음부터,
     * 잘못된 커서는 INVALID_CURSOR. ON_SALE은 생성물 계산 입력 전체, HIDDEN은 최소 필드만.
     * 평점·리뷰수는 저장 없이 조회 시 집계(02 D9) — product.updated_at 갱신 시점 스냅샷.
     */
    public ProductChangesResponse getChanges(String since, Integer limit) {
        int size = limit == null ? SYNC_DEFAULT_LIMIT : Math.min(Math.max(limit, 1), SYNC_MAX_LIMIT);
        ProductChangeCursor cursor = ProductChangeCursor.decode(since);
        List<Product> rows = productRepository.findChangesSince(
                cursor == null ? null : cursor.updatedAt(),
                cursor == null ? null : cursor.id(),
                PageRequest.of(0, size + 1)); // +1로 hasMore 판별
        boolean hasMore = rows.size() > size;
        List<Product> page = hasMore ? rows.subList(0, size) : rows;
        String nextCursor = page.isEmpty()
                ? (since == null || since.isBlank() ? "0" : since) // 빈 결과는 요청 since 그대로 echo
                : ProductChangeCursor.encode(page.get(page.size() - 1).getUpdatedAt(),
                        page.get(page.size() - 1).getId());
        return new ProductChangesResponse(toChangeItems(page), nextCursor, hasMore);
    }

    /** ON_SALE만 이름·평점·집계를 채운다(HIDDEN은 최소 필드) — 배치 lookup으로 N+1 회피 */
    private List<ProductChangesResponse.Item> toChangeItems(List<Product> products) {
        List<Product> onSale = products.stream()
                .filter(p -> p.getStatus() == ProductStatus.ON_SALE).toList();
        Map<Long, String> categoryNames = categoryService.getNames(
                onSale.stream().map(Product::getCategoryId).collect(Collectors.toSet()));
        Map<Long, String> brandNames = brandService.getNames(
                onSale.stream().map(Product::getBrandId).collect(Collectors.toSet()));
        Map<Long, RatingStats> stats = reviewService.getStats(
                onSale.stream().map(Product::getId).toList());
        return products.stream().map(p -> {
            OffsetDateTime updatedAt = p.getUpdatedAt().atZone(SEOUL).toOffsetDateTime();
            if (p.getStatus() != ProductStatus.ON_SALE) {
                return ProductChangesResponse.Item.hidden(p.getId(), updatedAt);
            }
            RatingStats s = stats.getOrDefault(p.getId(), RatingStats.EMPTY);
            return ProductChangesResponse.Item.onSale(p.getId(), updatedAt, p.getName(),
                    categoryNames.get(p.getCategoryId()), brandNames.get(p.getBrandId()),
                    p.getPrice(), s.average(), s.count(), parseJson(p.getAttributes()));
        }).toList();
    }

    /** P-6 상품 목록 — HIDDEN 제외, popular는 표시 판매량(02 D18) 기준 */
    public ProductCardPageResponse getBrandProducts(Long brandId, Long categoryId, String sort,
                                                    int page, int size) {
        Page<Product> productPage = switch (sort) {
            case "latest" -> findBrandPage(brandId, categoryId,
                    PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
            case "price_asc" -> findBrandPage(brandId, categoryId,
                    PageRequest.of(page, size, Sort.by(Sort.Order.asc("price"), Sort.Order.desc("id"))));
            case "price_desc" -> findBrandPage(brandId, categoryId,
                    PageRequest.of(page, size, Sort.by(Sort.Order.desc("price"), Sort.Order.desc("id"))));
            default -> productRepository.findBrandProductsOrderByPopularity(brandId, categoryId,
                    PageRequest.of(page, size));
        };
        return ProductCardPageResponse.from(productPage, toCards(productPage.getContent()));
    }

    /** M-7 — 최근 본 상품: 중복 제거 최신 20개 (04 §5, 02 D3) */
    public List<ProductCardResponse> getRecent(Long memberId, int size) {
        return getCardsByIds(productRepository.findRecentViewedIds(memberId, size));
    }

    /**
     * 카드 다건 조회 — 입력 id 순서 보존. HIDDEN도 유지(purchasable=false) —
     * 찜·최근 본 상품은 개인 목록이라 장바구니(C-1)와 같은 원칙, 공개 목록(P-4/P-6/CH-5)과 다름.
     */
    public List<ProductCardResponse> getCardsByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Product> products = productRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        return toCards(ids.stream().map(products::get).filter(java.util.Objects::nonNull).toList());
    }

    /** P-6 브랜드홈 필터 축 — 해당 브랜드 판매 중 상품의 소분류 (02 D20) */
    public List<Long> getBrandCategoryIds(Long brandId) {
        return productRepository.findCategoryIdsByBrand(brandId);
    }

    /** P-4/I-3 공용 — 7일 판매수 → product_view 수 → 최신순 순으로 채운 인기 id */
    private List<Long> popularIds(int size) {
        LocalDateTime since = LocalDateTime.now().minusDays(POPULAR_DAYS);
        List<Long> ids = new ArrayList<>(productRepository.findPopularIdsBySales(since, size));
        if (ids.size() < size) {
            ids.addAll(productRepository.findPopularIdsByViews(since, excluded(ids), size - ids.size()));
        }
        if (ids.size() < size) {
            ids.addAll(productRepository.findLatestIds(excluded(ids), size - ids.size()));
        }
        return ids;
    }

    private List<Product> findByIdsPreservingOrder(List<Long> ids) {
        Map<Long, Product> products = productRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        return ids.stream().map(products::get).filter(java.util.Objects::nonNull).toList();
    }

    /** 카테고리·브랜드명은 배치 lookup으로 N+1 회피 — 평점은 행에 이미 실려 온다 */
    private List<ProductCandidateResponse> toCandidates(List<CandidateRow> rows) {
        List<Product> products = rows.stream().map(CandidateRow::product).toList();
        Map<Long, String> categoryNames = categoryService.getNames(
                products.stream().map(Product::getCategoryId).collect(Collectors.toSet()));
        Map<Long, String> brandNames = brandService.getNames(
                products.stream().map(Product::getBrandId).collect(Collectors.toSet()));
        return rows.stream()
                .map(row -> {
                    Product p = row.product();
                    return ProductCandidateResponse.from(p, parseJson(p.getAttributes()),
                            categoryNames.get(p.getCategoryId()), brandNames.get(p.getBrandId()),
                            RatingStats.of(row.reviewCount(), row.ratingAverage()));
                })
                .toList();
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Page<Product> findBrandPage(Long brandId, Long categoryId, Pageable pageable) {
        return categoryId == null
                ? productRepository.findAllByBrandIdAndStatus(brandId, ProductStatus.ON_SALE, pageable)
                : productRepository.findAllByBrandIdAndCategoryIdAndStatus(brandId, categoryId,
                        ProductStatus.ON_SALE, pageable);
    }

    private List<ProductCardResponse> toCards(List<Product> products) {
        List<Long> ids = products.stream().map(Product::getId).toList();
        Map<Long, RatingStats> stats = reviewService.getStats(ids);
        Map<Long, String> brandNames = brandService.getNames(
                products.stream().map(Product::getBrandId).collect(Collectors.toSet()));
        return products.stream()
                .map(p -> ProductCardResponse.from(p, brandNames.get(p.getBrandId()),
                        stats.getOrDefault(p.getId(), RatingStats.EMPTY)))
                .toList();
    }

    private Product getProduct(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    /** NOT IN 파라미터는 빈 리스트 불가 — 매칭 불가능한 센티널로 대체 */
    private static List<Long> excluded(List<Long> ids) {
        return ids.isEmpty() ? List.of(-1L) : ids;
    }

    private JsonNode parseJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("attributes JSON 파싱 실패 — null로 응답: {}", json, e);
            return null;
        }
    }
}
