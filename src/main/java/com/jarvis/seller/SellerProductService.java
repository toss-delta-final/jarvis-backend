package com.jarvis.seller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.brand.BrandRepository;
import com.jarvis.category.Category;
import com.jarvis.category.CategoryRepository;
import com.jarvis.global.cache.RedisCache;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.order.OrderItemRepository;
import com.jarvis.product.Product;
import com.jarvis.product.ProductChangeLog;
import com.jarvis.product.ProductChangeLogRepository;
import com.jarvis.product.ProductChangeType;
import com.jarvis.product.ProductOption;
import com.jarvis.product.ProductOptionRepository;
import com.jarvis.product.ProductRepository;
import com.jarvis.product.ProductService;
import com.jarvis.product.ProductStatus;
import com.jarvis.product.ProductStock;
import com.jarvis.product.ProductStockRepository;
import com.jarvis.seller.dto.SellerProductCreateRequest;
import com.jarvis.seller.dto.SellerProductCreateResponse;
import com.jarvis.seller.dto.SellerProductDeleteResponse;
import com.jarvis.seller.dto.SellerProductInternalListResponse;
import com.jarvis.seller.dto.SellerProductItemResponse;
import com.jarvis.seller.dto.SellerProductListResponse;
import com.jarvis.seller.dto.SellerProductUpdateRequest;
import com.jarvis.seller.dto.SellerProductUpdateResponse;
import com.jarvis.seller.dto.StockInput;
import com.jarvis.seller.dto.StockView;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-3=I-9(같은 목록) + 상품 쓰기(I-10/I-11/I-12) 서비스 (04 §7·§10).
 * change log는 어휘가 있는 PRICE(판매가)/STOCK/STATUS만 기록 — 응답 changes[]도 이 어휘만 (노션 I-11).
 * 상품 수정은 챗봇 경로(I-11, HITL)만 — 소유 아님은 404로 존재 은닉(productId가 LLM 값).
 */
@Service
@RequiredArgsConstructor
public class SellerProductService {

    /** I-9는 최신순 고정(노션 I-9 — 정렬 파라미터 없음). S-3 정렬은 인메모리 comparator */
    private static final Sort LATEST_SORT = Sort.by(Sort.Direction.DESC, "id");

    private static final String PLACEHOLDER_IMAGE = "/images/placeholder.webp";
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final ProductRepository productRepository;
    private final ProductStockRepository productStockRepository;
    private final ProductOptionRepository productOptionRepository;
    private final OrderItemRepository orderItemRepository;
    private final CategoryRepository categoryRepository;
    private final ProductChangeLogRepository productChangeLogRepository;
    private final BrandRepository brandRepository;
    private final ObjectMapper objectMapper;
    private final RedisCache cache;
    private final ProductImageStorage productImageStorage;

    private static final List<String> PRODUCT_SORTS = List.of("latest", "sales", "stock", "price");

    /**
     * S-3 — 판매자 화면 목록 (노션 S-3). 브랜드 전 상품 로드 후 파생(displayStatus·tabCounts)·정렬·페이징을
     * 서비스에서 수행 — 판매량 정렬이 파생값(base_sales_count + PAID 집계)이라 컬럼 정렬로 불가, 시드 규모가
     * 작아 전건 로드(S-1과 동일 패턴). q·offset 기반 챗봇 목록(I-9)은 findSellerProducts를 그대로 쓴다(불변).
     * sort: latest(기본, 등록일 desc) | sales(판매량 desc) | stock(재고 asc) | price(판매가 asc).
     */
    @Transactional(readOnly = true)
    public SellerProductListResponse list(Long brandId, String statusParam, String sort,
                                          int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.PRODUCT_INVALID_PARAM);
        }
        SellerDisplayStatus filter = parseDisplayStatus(statusParam);
        String sortKey = parseProductSort(sort);

        List<Product> all = productRepository.findAllByBrandIdAndStatusNot(brandId, ProductStatus.DELETED);
        Map<Long, Long> salesById = paidQuantities(all);
        // 재고는 product_stock의 옵션별 합계다 (02 D33 개정) — 전건 로드 화면이라 한 번에 받는다.
        // 합계 0과 "살 수 있는 옵션 없음"은 같은 뜻이라(수량이 음수일 수 없다) 판정식은 그대로 쓴다
        Map<Long, Integer> stocks = productStockRepository.sumMap(
                all.stream().map(Product::getId).toList());
        Map<String, Long> tabCounts = productTabCounts(all, stocks);

        List<Product> matched = all.stream()
                .filter(p -> filter == null || filter.matches(p.getStatus(), stockOf(stocks, p)))
                .sorted(productComparator(sortKey, salesById, stocks))
                .toList();

        long total = matched.size();
        long fromLong = (long) page * size;
        int fromIndex = fromLong >= matched.size() ? matched.size() : (int) fromLong;
        List<Product> pageItems = matched.subList(fromIndex, Math.min(fromIndex + size, matched.size()));
        Map<Long, String> categoryNames = categoryNames(pageItems);

        List<SellerProductListResponse.Row> rows = pageItems.stream()
                .map(p -> new SellerProductListResponse.Row(p.getId(), p.getName(), p.getImageUrl(),
                        categoryNames.get(p.getCategoryId()), p.getPrice(), p.getOriginalPrice(),
                        stockOf(stocks, p), displayedSalesCount(p, salesById), p.getStatus().name(),
                        SellerDisplayStatus.of(p.getStatus(), stockOf(stocks, p)).name(),
                        toKst(p.getCreatedAt()), toKst(p.getUpdatedAt())))
                .toList();
        int totalPages = (int) Math.ceil((double) total / size);
        return new SellerProductListResponse(tabCounts, rows, page, size, total, totalPages);
    }

    private static SellerDisplayStatus parseDisplayStatus(String value) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        try {
            return SellerDisplayStatus.valueOf(v);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PRODUCT_INVALID_PARAM);
        }
    }

    private static String parseProductSort(String sort) {
        String v = sort == null || sort.isBlank() ? "latest" : sort;
        if (!PRODUCT_SORTS.contains(v)) {
            throw new BusinessException(ErrorCode.PRODUCT_INVALID_PARAM);
        }
        return v;
    }

    /** tabCounts는 필터와 무관하게 항상 전량 기준 (노션 S-3) — displayStatus 파생으로 집계 */
    private static Map<String, Long> productTabCounts(List<Product> products,
                                                      Map<Long, Integer> stocks) {
        long onSale = 0;
        long soldOut = 0;
        long hidden = 0;
        for (Product p : products) {
            switch (SellerDisplayStatus.of(p.getStatus(), stockOf(stocks, p))) {
                case ON_SALE -> onSale++;
                case SOLD_OUT -> soldOut++;
                case HIDDEN -> hidden++;
            }
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("ALL", (long) products.size());
        counts.put("ON_SALE", onSale);
        counts.put("SOLD_OUT", soldOut);
        counts.put("HIDDEN", hidden);
        return counts;
    }

    /**
     * 판매량은 파생값(base + PAID 집계)이라 salesById로 계산해 비교. 정렬 방향은 노션 미명시분에 대한
     * 합리적 기본값(sales desc / stock asc / price asc). latest는 id desc — created_at과 단조 등가라 더 저렴.
     */
    private Comparator<Product> productComparator(String sortKey, Map<Long, Long> salesById,
                                                  Map<Long, Integer> stocks) {
        Comparator<Product> byIdDesc = Comparator.comparingLong(Product::getId).reversed();
        return switch (sortKey) {
            case "sales" -> Comparator
                    .comparingLong((Product p) -> displayedSalesCount(p, salesById)).reversed()
                    .thenComparing(byIdDesc);
            case "stock" -> Comparator.comparingInt((Product p) -> stockOf(stocks, p))
                    .thenComparing(byIdDesc);
            case "price" -> Comparator.comparingInt(Product::getPrice).thenComparing(byIdDesc);
            default -> byIdDesc;
        };
    }

    /** I-9 — 챗봇용 동일 목록 (노션 I-9) — {rows, total(필터 적용 전체 건수)}, offset은 진짜 row offset */
    @Transactional(readOnly = true)
    public SellerProductInternalListResponse listInternal(Long brandId, ProductStatus status,
                                                          String q, int limit, int offset) {
        if (!brandRepository.existsById(brandId)) {
            throw new BusinessException(ErrorCode.BRAND_NOT_FOUND);
        }
        Page<Product> products = productRepository.findSellerProducts(brandId, status, blankToNull(q),
                new OffsetPageRequest(offset, limit, LATEST_SORT));
        Map<Long, Long> salesById = paidQuantities(products.getContent());
        Map<Long, String> categoryNames = categoryNames(products.getContent());
        // 에이전트가 I-11에 넣을 optionId의 출처 — 품절 옵션도 그대로 내린다(판매자는 알아야 채운다)
        Map<Long, List<StockView>> stockViews = stockViews(products.getContent());
        List<SellerProductItemResponse> rows = products.getContent().stream()
                .map(p -> new SellerProductItemResponse(p.getId(), p.getName(), p.getSummary(),
                        parseJson(p.getAttributes()), p.getDescription(), p.getPrice(),
                        p.getOriginalPrice(), p.getStatus().name(),
                        totalQuantity(stockViews.get(p.getId())),
                        stockViews.getOrDefault(p.getId(), List.of()),
                        displayedSalesCount(p, salesById), categoryNames.get(p.getCategoryId()),
                        p.getImageUrl()))
                .toList();
        return new SellerProductInternalListResponse(rows, products.getTotalElements());
    }

    /**
     * I-11 — internal 경로 수정 (HITL confirm 후, 소유 아님 404 — 존재 은닉).
     * price ≤ originalPrice(02 D28), stockQuantity ≥ 0, 동일값 미기록.
     * changes는 change log가 남은 어휘만(PRICE/STOCK/STATUS — 노션 I-11), 그 외 필드 변경은 미포함.
     */
    @Transactional
    public SellerProductUpdateResponse updateInternal(Long brandId, Long productId,
                                                      SellerProductUpdateRequest request) {
        if (request.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        Product product = ownedProduct(brandId, productId);
        if (product.getStatus() == ProductStatus.DELETED) {
            throw new BusinessException(ErrorCode.PRODUCT_DELETED);
        }
        // 삭제는 I-12 전용 전이 — 수정으로 도달하면 재삭제 검사(ALREADY_DELETED)를 건너뛴다.
        // 어휘는 VALIDATION_ERROR — 이 API가 허용하지 않는 status 값이라는 점에서 SOLD_OUT과 같은 부류다
        // (PRODUCT_INVALID_PARAM은 S-3·I-9의 query 파라미터 전용)
        if (request.status() == ProductStatus.DELETED) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        validateStocks(request.resolvedStocks());
        validatePriceRange(
                request.price() != null ? request.price() : product.getPrice(),
                request.originalPrice() != null ? request.originalPrice() : product.getOriginalPrice());

        List<String> changes = new ArrayList<>();
        if (changed(request.name(), product.getName())) {
            product.changeName(request.name());
        }
        if (changed(request.summary(), product.getSummary())) {
            product.changeSummary(request.summary());
        }
        String attributes = toJsonString(request.attributes());
        if (changed(attributes, product.getAttributes())) {
            product.changeAttributes(attributes);
        }
        if (request.description() != null) {
            String sanitized = sanitizeDescription(request.description());
            if (changed(sanitized, product.getDescription())) {
                product.changeDescription(sanitized);
            }
        }
        if (changed(request.imageUrl(), product.getImageUrl())) {
            product.changeImageUrl(resolveImageUrl(request.imageUrl(), productId));
        }
        if (request.price() != null && request.price() != product.getPrice()) {
            recordLog(productId, ProductChangeType.PRICE, product.getPrice(), request.price());
            changes.add(ProductChangeType.PRICE.name());
            product.changePrice(request.price());
        }
        if (request.originalPrice() != null && request.originalPrice() != product.getOriginalPrice()) {
            product.changeOriginalPrice(request.originalPrice());
        }
        if (request.status() != null && request.status() != product.getStatus()) {
            productChangeLogRepository.save(ProductChangeLog.of(productId, ProductChangeType.STATUS,
                    product.getStatus().name(), request.status().name()));
            changes.add(ProductChangeType.STATUS.name());
            product.changeStatus(request.status());
        }
        if (applyStocks(productId, request.resolvedStocks())) {
            changes.add(ProductChangeType.STOCK.name());
        }
        // 상세 정적조각 무효화 — 재고·가격은 조각에 없지만 이름·이미지·옵션이 바뀔 수 있다 (07 §3-1)
        cache.evictAfterCommit(ProductService.DETAIL_FRAGMENT_KEY_PREFIX + productId);
        return SellerProductUpdateResponse.of(productId, product.getPrice(),
                stockViews(List.of(product)).getOrDefault(productId, List.of()),
                product.getStatus().name(), changes);
    }

    /**
     * I-10 등록 — 등록은 change log 미기록 (04 §10), 카테고리는 소분류(leaf)만 (02 D26②).
     * 필수값(name·price·stockQuantity·categoryId) 누락은 422 MISSING_FIELD (노션 I-10 — bean validation 400 대신).
     */
    @Transactional
    public SellerProductCreateResponse create(Long brandId, SellerProductCreateRequest request) {
        requireFields(request);
        // 삭제 상태로 상품을 만드는 건 의미가 없다 — 만들자마자 아무에게도 안 보인다
        if (request.status() == ProductStatus.DELETED) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        List<StockInput> stocks = request.resolvedStocks();
        validateStocks(stocks);
        // 등록은 옵션을 만들지 않는다 — stocks는 optionId=null 한 줄이어야 한다 (노션 I-10, 2026-08-09)
        if (stocks.size() != 1 || stocks.get(0).optionId() != null) {
            throw new BusinessException(ErrorCode.INVALID_STOCK);
        }
        int originalPrice = request.originalPrice() != null ? request.originalPrice() : request.price();
        validatePriceRange(request.price(), originalPrice);
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_CATEGORY_INVALID));
        if (category.isRoot()) {
            throw new BusinessException(ErrorCode.PRODUCT_CATEGORY_INVALID);
        }
        Product product = Product.create(brandId, request.categoryId(), request.name(), originalPrice,
                request.price(),
                request.imageUrl() != null ? request.imageUrl() : PLACEHOLDER_IMAGE,
                request.summary(), toJsonString(request.attributes()),
                sanitizeDescription(request.description()),
                request.status() != null ? request.status() : ProductStatus.ON_SALE);
        Product saved = productRepository.save(product);
        // 이미지 복사는 여기서만 가능하다 — S-6은 발급 시점에 productId를 모른다 (04 §7 S-6, 2026-08-10)
        saved.changeImageUrl(resolveImageUrl(saved.getImageUrl(), saved.getId()));
        // 재고 행은 상품과 함께 생긴다 — 없으면 그 상품은 영원히 품절이다 (02 D33 개정)
        productStockRepository.save(ProductStock.of(saved.getId(), null,
                stocks.get(0).quantity()));
        return new SellerProductCreateResponse(saved.getId(), saved.getStatus().name());
    }

    /**
     * S-6이 준 임시 주소일 때만 상품 경로로 옮긴다 (04 §7 S-6, 2026-08-10).
     *
     * <p>이미 최종 주소이거나 플레이스홀더·외부 주소면 그대로 둔다 — 가격만 고치는 I-11 호출이
     * 매번 S3를 때리지 않게 하려는 것이다. 복사가 실패하면 예외가 올라가 트랜잭션이 통째로 롤백된다.
     */
    private String resolveImageUrl(String imageUrl, Long productId) {
        return productImageStorage.isTemporary(imageUrl)
                ? productImageStorage.moveToProduct(imageUrl, productId)
                : imageUrl;
    }

    /**
     * I-12 — soft delete(DELETED 전환)만, hard delete 문 없음. 이미 DELETED면 409 (노션 I-12).
     * HIDDEN에서 DELETED로 가는 건 정상 전이다 — 숨겨둔 상품을 나중에 지우는 흐름을 막지 않는다.
     */
    @Transactional
    public SellerProductDeleteResponse softDelete(Long brandId, Long productId) {
        Product product = ownedProduct(brandId, productId);
        if (product.getStatus() == ProductStatus.DELETED) {
            throw new BusinessException(ErrorCode.ALREADY_DELETED);
        }
        productChangeLogRepository.save(ProductChangeLog.of(productId, ProductChangeType.STATUS,
                product.getStatus().name(), ProductStatus.DELETED.name()));
        product.changeStatus(ProductStatus.DELETED);
        cache.evictAfterCommit(ProductService.DETAIL_FRAGMENT_KEY_PREFIX + productId);
        return new SellerProductDeleteResponse(productId, ProductStatus.DELETED.name());
    }

    /**
     * 소유권 검증 — productId는 LLM이 채우는 값이라 신뢰 불가, internal에서도 반복 (05 §I-9).
     * internal 경로(I-11/I-12)라 소유 아님도 404로 은닉 — 존재 여부 미노출 (노션 I-11).
     * 쓰기 경로(수정·soft delete)라 비관적 락으로 로드 — 재고 절대값 수정이 주문 차감과 경합해도 lost update 없음 (02 D33).
     */
    private Product ownedProduct(Long brandId, Long productId) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        if (!product.getBrandId().equals(brandId)) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        return product;
    }

    private Map<Long, Long> paidQuantities(List<Product> products) {
        if (products.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = products.stream().map(Product::getId).toList();
        return orderItemRepository.sumPaidQuantityByProduct(ids).stream()
                .collect(Collectors.toMap(OrderItemRepository.ProductQuantityRow::getProductId,
                        OrderItemRepository.ProductQuantityRow::getQuantity));
    }

    private Map<Long, String> categoryNames(List<Product> products) {
        List<Long> categoryIds = products.stream().map(Product::getCategoryId).distinct().toList();
        return categoryRepository.findAllById(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName, (a, b) -> a));
    }

    private static long displayedSalesCount(Product product, Map<Long, Long> salesById) {
        return product.getBaseSalesCount() + salesById.getOrDefault(product.getId(), 0L);
    }

    /** 노션 I-10 — 필수값 누락은 400 대신 422 MISSING_FIELD, 메시지에 누락 필드명 명시 */
    private static void requireFields(SellerProductCreateRequest request) {
        List<String> missing = new ArrayList<>();
        if (request.name() == null || request.name().isBlank()) {
            missing.add("name");
        }
        if (request.price() == null) {
            missing.add("price");
        }
        if (request.resolvedStocks() == null || request.resolvedStocks().isEmpty()) {
            missing.add("stocks");
        }
        if (request.categoryId() == null) {
            missing.add("categoryId");
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.MISSING_FIELD,
                    "필수 입력값이 누락되었습니다: " + String.join(", ", missing));
        }
    }

    private void validatePriceRange(int price, int originalPrice) {
        if (price > originalPrice) {
            throw new BusinessException(ErrorCode.INVALID_PRICE);
        }
    }

    /**
     * 수량 음수와 중복 옵션을 막는다 (422 INVALID_STOCK). 소속 검증은 {@link #applyStocks}에서
     * 실제 재고 행을 찾을 때 함께 걸린다 — 새 code를 만들지 않고 기존 재고 오류에 접었다
     * (2026-08-05 "합의되지 않은 새 code를 계약에 만들지 않는다").
     */
    private static void validateStocks(List<StockInput> stocks) {
        if (stocks == null) {
            return;
        }
        Set<Long> seen = new HashSet<>();
        for (StockInput stock : stocks) {
            if (stock == null || stock.quantity() == null || stock.quantity() < 0) {
                throw new BusinessException(ErrorCode.INVALID_STOCK);
            }
            // 같은 옵션이 두 번 오면 어느 값이 이기는지가 요청 순서에 달린다 — 받지 않는다
            if (!seen.add(stock.optionId())) {
                throw new BusinessException(ErrorCode.INVALID_STOCK);
            }
        }
    }

    /**
     * I-11 재고 반영 — <b>배열에 실린 옵션만</b> 갱신하는 부분 수정이다(노션 I-11).
     * 값이 실제로 바뀐 옵션마다 STOCK 로그를 한 행씩 남긴다(02 D32·D33 개정).
     *
     * <p>행을 잠그고 읽는다 — 절대값 수정이 주문의 조건부 차감과 경합하면 판매분이 유령 복원된다.
     *
     * @return 한 옵션이라도 실제로 바뀌었으면 true (changes에 STOCK을 넣을지 판단용)
     */
    private boolean applyStocks(Long productId, List<StockInput> stocks) {
        if (stocks == null || stocks.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (StockInput input : stocks) {
            ProductStock stock = productStockRepository
                    .findOneForUpdate(productId, input.optionId())
                    // 그 상품의 옵션이 아니거나(옵션 있는 상품에 null 포함) 재고 행이 없는 경우
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_STOCK));
            if (stock.getQuantity() == input.quantity()) {
                continue;
            }
            productChangeLogRepository.save(ProductChangeLog.ofOption(productId, input.optionId(),
                    ProductChangeType.STOCK, String.valueOf(stock.getQuantity()),
                    String.valueOf(input.quantity())));
            stock.changeQuantity(input.quantity());
            changed = true;
        }
        return changed;
    }

    /** productId → 옵션별 재고 뷰. 옵션 없는 상품은 optionId·optionName이 null인 한 줄이다 */
    private Map<Long, List<StockView>> stockViews(List<Product> products) {
        List<Long> productIds = products.stream().map(Product::getId).toList();
        if (productIds.isEmpty()) {
            return Map.of();
        }
        List<ProductStock> stocks = productStockRepository.findAllByProductIdIn(productIds);
        Map<Long, String> optionNames = productOptionRepository.findAllById(
                        stocks.stream().map(ProductStock::getOptionId).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(ProductOption::getId, ProductOption::getName));
        Map<Long, List<StockView>> byProduct = new LinkedHashMap<>();
        for (ProductStock stock : stocks) {
            byProduct.computeIfAbsent(stock.getProductId(), k -> new ArrayList<>())
                    .add(new StockView(stock.getOptionId(),
                            stock.getOptionId() == null ? null : optionNames.get(stock.getOptionId()),
                            stock.getQuantity()));
        }
        return byProduct;
    }

    private static int totalQuantity(List<StockView> views) {
        return views == null ? 0 : views.stream().mapToInt(StockView::quantity).sum();
    }

    private static int stockOf(Map<Long, Integer> stocks, Product product) {
        return stocks.getOrDefault(product.getId(), 0);
    }

    /** attributes는 JSON 객체로 수신(노션) — 저장 컬럼(json 문자열) 형식은 기존과 동일하게 직렬화 */
    private static String toJsonString(JsonNode node) {
        return node == null ? null : node.toString();
    }

    private JsonNode parseJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return null;
        }
    }

    private static OffsetDateTime toKst(LocalDateTime time) {
        return time == null ? null : time.atZone(ZONE).toOffsetDateTime();
    }

    private void recordLog(Long productId, ProductChangeType type, int oldValue, int newValue) {
        productChangeLogRepository.save(ProductChangeLog.of(productId, type,
                String.valueOf(oldValue), String.valueOf(newValue)));
    }

    private static boolean changed(String requested, String current) {
        return requested != null && !Objects.equals(requested, current);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 서버측 XSS 최소 방어 (04 §10 I-10/I-11) — 실행 가능 태그·인라인 핸들러·javascript: 제거.
     * 라이브러리 없이 정규식 수준 — 고도화 시 전용 sanitizer로 교체.
     */
    static String sanitizeDescription(String description) {
        if (description == null) {
            return null;
        }
        return description
                .replaceAll("(?is)<(script|style|iframe|object|embed)[^>]*>.*?</\\1\\s*>", "")
                .replaceAll("(?is)<(script|style|iframe|object|embed)[^>]*/?>", "")
                .replaceAll("(?i)\\son\\w+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", "")
                .replaceAll("(?i)javascript\\s*:", "");
    }

}
