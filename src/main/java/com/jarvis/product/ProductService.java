package com.jarvis.product;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.brand.BrandService;
import com.jarvis.category.CategoryService;
import com.jarvis.global.cache.RedisCache;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.product.dto.CandidateRow;
import com.jarvis.product.dto.PopularCardResponse;
import com.jarvis.product.dto.ProductCandidateResponse;
import com.jarvis.product.dto.ProductCardPageResponse;
import com.jarvis.product.dto.ProductCardResponse;
import com.jarvis.product.dto.ProductDetailFragment;
import com.jarvis.product.dto.ProductDetailResponse;
import com.jarvis.product.dto.ProductChangesResponse;
import com.jarvis.review.ReviewService;
import com.jarvis.review.dto.RatingStats;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
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
    private static final int CANDIDATE_OPTION_LIMIT = 20; // I-1 후보의 옵션 노출 상한 (05 §I-1 — 2026-08-03)
    /** 07 §2 키 지도. `v1:`은 값 스키마가 바뀌면 버전만 올려 통째로 버리기 위한 프리픽스다 */
    private static final String POPULAR_KEY = "v1:popular:cards";
    /** FE staleTime(5분)보다 짧게 — FE 재조회가 항상 새 순위를 받도록 (07 §2-1, 2026-08-10) */
    private static final Duration POPULAR_TTL = Duration.ofMinutes(3);
    /** P-4의 size 상한(@Max(50))과 같은 값 — 여기까지 계산해두면 모든 P-4 요청을 덮는다 */
    private static final int POPULAR_CACHE_SIZE = 50;
    /** 상세 정적조각 (07 §3-1) — evict 지점(판매자 수정·삭제)이 다른 서비스라 키를 공개한다 */
    public static final String DETAIL_FRAGMENT_KEY_PREFIX = "v1:product:frag:";
    /** evict가 신선도를 책임지므로 TTL은 안전망 — 길게 둬도 잃는 게 없다 (07 §2-1) */
    private static final Duration DETAIL_FRAGMENT_TTL = Duration.ofHours(1);
    private static final Pattern REGEX_META = Pattern.compile("[\\\\^$.|?*+()\\[\\]{}]");

    private final ProductRepository productRepository;
    private final ProductStockRepository productStockRepository;
    private final RedisCache cache;
    private final ProductOptionRepository productOptionRepository;
    private final ProductDetailImageRepository productDetailImageRepository;
    private final ImageProperties imageProperties;
    private final BrandService brandService;
    private final CategoryService categoryService;
    private final ReviewService reviewService;
    private final ObjectMapper objectMapper;

    /**
     * P-2 — HIDDEN도 응답한다(purchaseState=HIDDEN): 장바구니가 HIDDEN 아이템을 유지(C-1)하므로
     * 상세 링크가 404가 되면 안 됨. 목록(P-4/P-6/CH-5)에서는 제외.
     *
     * <p>정적 조각(카테고리·브랜드 요약·옵션 정체·이미지 URL)은 캐시하고 판매자 수정·삭제가
     * evict한다(07 §3-1, 2026-08-10 부하 실측 후). 상품 행·재고는 매 요청 DB, 평점은 자체
     * 캐시(ReviewService) — 적중 시 요청당 DB 왕복이 7 → 2로 준다. FE가 no-store라 상세는
     * 모든 요청이 서버까지 오는 경로다.
     */
    public ProductDetailResponse getDetail(Long id) {
        Product product = getProduct(id);
        // 상세는 옵션마다 재고를 보여준다 — 합계와 옵션별을 같은 조회에서 뽑는다 (02 D33 개정)
        List<ProductStock> stocks = productStockRepository.findAllByProductId(id);
        Map<Long, Integer> stockByOption = stocks.stream()
                .filter(stock -> stock.getOptionId() != null)
                .collect(Collectors.toMap(ProductStock::getOptionId, ProductStock::getQuantity));
        int total = stocks.stream().mapToInt(ProductStock::getQuantity).sum();
        ProductDetailFragment fragment = cache.get(DETAIL_FRAGMENT_KEY_PREFIX + id,
                DETAIL_FRAGMENT_TTL, new TypeReference<>() { }, () -> loadDetailFragment(product));
        return ProductDetailResponse.of(product, parseJson(product.getAttributes()), fragment,
                reviewService.getStats(id), total, stockByOption);
    }

    private ProductDetailFragment loadDetailFragment(Product product) {
        return new ProductDetailFragment(
                ProductDetailResponse.CategorySummary.from(
                        categoryService.getCategory(product.getCategoryId())),
                ProductDetailResponse.BrandSummary.from(brandService.getBrand(product.getBrandId())),
                productOptionRepository.findAllByProductIdOrderByIdAsc(product.getId()).stream()
                        .map(option -> new ProductDetailFragment.OptionEntry(
                                option.getId(), option.getName(), option.getExtraPrice()))
                        .toList(),
                detailImageUrls(product.getId()));
    }

    /**
     * P-2 상세 이미지 (02 D42) — 자르지 않고 전량. 최대 179장이어도 URL 배열은 약 7KB라
     * 응답 부담이 아니다. 실제 부하는 이미지 바이트(장당 평균 810KB)이며 접기·지연 로딩은 FE 소관.
     * 상세 이미지가 없는 상품(131건)은 빈 리스트가 되어 응답에 `[]`로 나간다.
     */
    private List<String> detailImageUrls(Long productId) {
        return productDetailImageRepository.findAllByProductIdOrderBySortOrderAsc(productId).stream()
                .map(image -> imageProperties.toUrl(image.getImageKey()))
                .toList();
    }

    /** P-4 — 7일 판매수 → product_view 수 → 최신순 순으로 채움 (04 §2). 품절은 집계 단계에서 이미 빠진다 */
    public List<PopularCardResponse> getPopular(int size) {
        return getPopularCards(size).stream()
                .map(PopularCardResponse::of)
                .toList();
    }

    /**
     * P-5 대체용 인기 상품 — P-4와 같은 목록이되 공통 카드 형태로 준다(노션 P-5 Fallback 규약).
     * P-5는 여기에 상관키·{@code reason}을 덧붙여야 해서 {@code purchaseState}가 남은 카드가 필요하다.
     */
    public List<ProductCardResponse> getPopularCards(int size) {
        return popularCards(size);
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
                                                           List<String> brandNames, List<String> colors) {
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
                minPrice, maxPrice, colorPattern(colors)));
    }

    /**
     * 복수 색상을 정규식 하나로 합친다 — JPQL이 리스트에 대한 동적 OR을 표현하지 못해서다.
     * 색상명은 사용자·LLM이 넘기는 자유 텍스트라 정규식 메타문자를 이스케이프한다(패턴 주입 차단).
     */
    private static String colorPattern(List<String> colors) {
        if (colors == null) {
            return null;
        }
        String pattern = colors.stream()
                .filter(ProductService::hasText)
                .map(color -> escapeRegex(color.trim().toLowerCase()))
                .distinct()
                .collect(Collectors.joining("|"));
        return pattern.isEmpty() ? null : pattern;
    }

    private static String escapeRegex(String value) {
        return REGEX_META.matcher(value).replaceAll("\\\\$0");
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
                    p.getDescription(), categoryNames.get(p.getCategoryId()), p.getBrandId(),
                    brandNames.get(p.getBrandId()),
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
     * 카드 다건 조회 — 입력 id 순서 보존. HIDDEN·품절도 유지(purchaseState로 이유 표시) —
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

    /**
     * P-4/P-5 대체 공용 — 7일 판매수 → product_view 수 → 최신순으로 채운 인기 <b>카드 완제품</b>.
     *
     * <p><b>캐시 대상</b>(07 §3-1) — 개인화가 없어 모두가 같은 값을 보고(적중률 100%),
     * 7일치 주문을 전부 세는 계산이라 인덱스가 좁혀줄 대상이 없다. P-5(개인화 추천)가 실패하면
     * 트래픽이 여기로 몰리므로 <b>LLM 장애가 DB 장애로 번지는 걸 막는 격리 장치</b>이기도 하다.
     *
     * <p>id가 아니라 <b>카드를 통째로</b> 캐시한다(2026-08-10, 부하 실측 후 전환) — id만 캐시하면
     * 적중해도 하이드레이션 3쿼리(상품·재고 + 평점·브랜드는 자체 캐시)가 매 요청 나간다. 대가로
     * 카드의 가격·품절이 최대 TTL만큼 낡는데, 홈 카드는 담기·결제 경로가 없고 상세·주문이 DB
     * 직독이라 수용(팀 결정 2026-08-10). P-5 폴백의 sellable() 필터도 이 낡은 purchaseState를 본다.
     *
     * <p>키를 하나로 두려고 <b>상한만큼 만들어 캐시하고 요청 size로 잘라 쓴다</b> — size별로 키를
     * 나누면 키가 폭발한다. 원본이 7일 누적 집계라 몇 분 낡음이 순위를 바꾸지 못한다.
     * I-3는 size 상한이 없어 상한을 넘는 요청은 캐시를 우회한다(그런 호출은 사실상 없다).
     */
    private List<ProductCardResponse> popularCards(int size) {
        if (size > POPULAR_CACHE_SIZE) {
            return toCards(findByIdsPreservingOrder(loadPopularIds(size)));
        }
        List<ProductCardResponse> cached = cache.get(POPULAR_KEY, POPULAR_TTL,
                new TypeReference<>() { },
                () -> toCards(findByIdsPreservingOrder(loadPopularIds(POPULAR_CACHE_SIZE))));
        return cached.size() > size ? List.copyOf(cached.subList(0, size)) : cached;
    }

    /** I-3 후보용 — 순위만 필요해 카드 캐시에서 id를 뽑는다. 엔티티·평점은 호출부가 새로 읽는다 */
    private List<Long> popularIds(int size) {
        if (size > POPULAR_CACHE_SIZE) {
            return loadPopularIds(size);
        }
        return popularCards(size).stream().map(ProductCardResponse::productId).toList();
    }

    private List<Long> loadPopularIds(int size) {
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

    /** 카테고리·브랜드명·옵션은 배치 lookup으로 N+1 회피 — 평점은 행에 이미 실려 온다 */
    private List<ProductCandidateResponse> toCandidates(List<CandidateRow> rows) {
        List<Product> products = rows.stream().map(CandidateRow::product).toList();
        Map<Long, String> categoryNames = categoryService.getNames(
                products.stream().map(Product::getCategoryId).collect(Collectors.toSet()));
        Map<Long, String> brandNames = brandService.getNames(
                products.stream().map(Product::getBrandId).collect(Collectors.toSet()));
        Map<Long, List<String>> optionNames = candidateOptionNames(products);
        return rows.stream()
                .map(row -> {
                    Product p = row.product();
                    // all은 이미 품절이 걸러진 목록이라 optionCount도 구매 가능한 것 기준이 된다
                    List<String> all = optionNames.getOrDefault(p.getId(), List.of());
                    List<String> shown = all.size() > CANDIDATE_OPTION_LIMIT
                            ? all.subList(0, CANDIDATE_OPTION_LIMIT) : all;
                    return ProductCandidateResponse.from(p, parseJson(p.getAttributes()),
                            categoryNames.get(p.getCategoryId()), brandNames.get(p.getBrandId()),
                            RatingStats.of(row.reviewCount(), row.ratingAverage()),
                            shown, all.size());
                })
                .toList();
    }

    /**
     * 후보 전체의 옵션명을 한 번에 — 빈 IN을 만들지 않도록 후보가 없으면 조회 자체를 건너뛴다.
     *
     * <p><b>품절 옵션은 빼고 센다</b>(2026-08-09, 노션 I-1 개정). 못 파는 옵션을 후보에 실으면
     * LLM이 그걸로 되물어서, 사용자가 골라도 담기에서 튕긴다. 여기서 걸러진 목록이 그대로
     * {@code options}가 되고 그 크기가 {@code optionCount}가 되므로 <b>두 값의 기준이 자동으로 같아진다</b> —
     * 어긋나면 AI의 정합 가드(I-1 optionCount ↔ I-2 detail.options)가 오작동한다.
     */
    private Map<Long, List<String>> candidateOptionNames(List<Product> products) {
        if (products.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = products.stream().map(Product::getId).toList();
        Set<Long> purchasable = productStockRepository.findAllByProductIdIn(ids).stream()
                .filter(stock -> stock.getOptionId() != null && stock.getQuantity() > 0)
                .map(ProductStock::getOptionId)
                .collect(Collectors.toSet());
        return productOptionRepository.findAllByProductIdInOrderByProductIdAscIdAsc(ids).stream()
                .filter(option -> purchasable.contains(option.getId()))
                .collect(Collectors.groupingBy(ProductOption::getProductId,
                        Collectors.mapping(ProductOption::getName, Collectors.toList())));
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
        // 카드마다 재고를 다시 묻지 않도록 한 번에 — 목록이라 N+1이 그대로 응답 시간이 된다
        Map<Long, Integer> stocks = productStockRepository.sumMap(ids);
        return products.stream()
                .map(p -> ProductCardResponse.from(p, brandNames.get(p.getBrandId()),
                        stats.getOrDefault(p.getId(), RatingStats.EMPTY),
                        stocks.getOrDefault(p.getId(), 0)))
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
