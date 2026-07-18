package com.jarvis.seller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.category.Category;
import com.jarvis.category.CategoryRepository;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.order.OrderItemRepository;
import com.jarvis.product.Product;
import com.jarvis.product.ProductChangeLog;
import com.jarvis.product.ProductChangeLogRepository;
import com.jarvis.product.ProductChangeType;
import com.jarvis.product.ProductRepository;
import com.jarvis.product.ProductStatus;
import com.jarvis.seller.dto.SellerProductCreateRequest;
import com.jarvis.seller.dto.SellerProductItemResponse;
import com.jarvis.seller.dto.SellerProductListResponse;
import com.jarvis.seller.dto.SellerProductUpdateRequest;
import com.jarvis.seller.dto.SellerProductUpdateResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-3=I-9(같은 목록), S-5=I-11(같은 검증·change log) 공용 서비스 (04 §7·§10).
 * change log는 어휘가 있는 PRICE(판매가)/STOCK/STATUS만 기록 — 그 외 필드 변경은 응답 changes[]로만.
 */
@Service
@RequiredArgsConstructor
public class SellerProductService {

    private static final String PLACEHOLDER_IMAGE = "/images/placeholder.webp";

    private final ProductRepository productRepository;
    private final OrderItemRepository orderItemRepository;
    private final CategoryRepository categoryRepository;
    private final ProductChangeLogRepository productChangeLogRepository;
    private final ObjectMapper objectMapper;

    /** S-3 — 판매자 화면 목록 (04 §7). sort: latest|price_asc|price_desc */
    @Transactional(readOnly = true)
    public SellerProductListResponse list(Long brandId, ProductStatus status, String q,
                                          String sort, int page, int size) {
        Page<Product> products = productRepository.findSellerProducts(
                brandId, status, blankToNull(q), PageRequest.of(page, size, toSort(sort)));
        Map<Long, Long> salesById = paidQuantities(products.getContent());
        Map<Long, String> categoryNames = categoryNames(products.getContent());
        List<SellerProductListResponse.Row> rows = products.getContent().stream()
                .map(p -> new SellerProductListResponse.Row(p.getId(), p.getName(), p.getPrice(),
                        p.getOriginalPrice(), p.getStockQuantity(), p.getStatus().name(),
                        displayedSalesCount(p, salesById), categoryNames.get(p.getCategoryId()),
                        p.getImageUrl()))
                .toList();
        return new SellerProductListResponse(rows, products.getNumber(), products.getSize(),
                products.getTotalElements(), products.getTotalPages());
    }

    /** I-9 — 챗봇용 동일 목록 (05 §I-9). offset은 limit 배수 그리드로 스냅(페이지 변환) */
    @Transactional(readOnly = true)
    public List<SellerProductItemResponse> listInternal(Long brandId, ProductStatus status,
                                                        String q, int limit, int offset) {
        int page = limit > 0 ? offset / limit : 0;
        Page<Product> products = productRepository.findSellerProducts(
                brandId, status, blankToNull(q), PageRequest.of(page, limit, toSort("latest")));
        Map<Long, Long> salesById = paidQuantities(products.getContent());
        return products.getContent().stream()
                .map(p -> new SellerProductItemResponse(p.getId(), p.getName(), p.getSummary(),
                        parseJson(p.getAttributes()), p.getDescription(), p.getPrice(),
                        p.getOriginalPrice(), p.getStatus().name(), p.getStockQuantity(),
                        displayedSalesCount(p, salesById)))
                .toList();
    }

    /**
     * S-5/I-11 공용 수정 — 소유권 403, price ≤ originalPrice(02 D28), 동일값 미기록.
     * 응답 changes[] 필드 어휘는 05 §1-3 draft와 동일(name/description/price/stockQuantity/status …).
     */
    @Transactional
    public SellerProductUpdateResponse update(Long brandId, Long productId,
                                              SellerProductUpdateRequest request) {
        if (request.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        Product product = ownedProduct(brandId, productId);
        validatePriceRange(
                request.price() != null ? request.price() : product.getPrice(),
                request.originalPrice() != null ? request.originalPrice() : product.getOriginalPrice());

        List<SellerProductUpdateResponse.Change> changes = new ArrayList<>();
        if (changed(request.name(), product.getName())) {
            changes.add(change("name", product.getName(), request.name()));
            product.changeName(request.name());
        }
        if (changed(request.summary(), product.getSummary())) {
            changes.add(change("summary", product.getSummary(), request.summary()));
            product.changeSummary(request.summary());
        }
        if (request.attributes() != null && changed(request.attributes(), product.getAttributes())) {
            requireValidJson(request.attributes());
            changes.add(change("attributes", product.getAttributes(), request.attributes()));
            product.changeAttributes(request.attributes());
        }
        if (request.description() != null) {
            String sanitized = sanitizeDescription(request.description());
            if (changed(sanitized, product.getDescription())) {
                changes.add(change("description", product.getDescription(), sanitized));
                product.changeDescription(sanitized);
            }
        }
        if (request.price() != null && request.price() != product.getPrice()) {
            recordLog(productId, ProductChangeType.PRICE, product.getPrice(), request.price());
            changes.add(change("price", String.valueOf(product.getPrice()), String.valueOf(request.price())));
            product.changePrice(request.price());
        }
        if (request.originalPrice() != null && request.originalPrice() != product.getOriginalPrice()) {
            changes.add(change("originalPrice", String.valueOf(product.getOriginalPrice()),
                    String.valueOf(request.originalPrice())));
            product.changeOriginalPrice(request.originalPrice());
        }
        if (request.status() != null && request.status() != product.getStatus()) {
            productChangeLogRepository.save(ProductChangeLog.of(productId, ProductChangeType.STATUS,
                    product.getStatus().name(), request.status().name()));
            changes.add(change("status", product.getStatus().name(), request.status().name()));
            product.changeStatus(request.status());
        }
        if (request.stockQuantity() != null && request.stockQuantity() != product.getStockQuantity()) {
            recordLog(productId, ProductChangeType.STOCK, product.getStockQuantity(), request.stockQuantity());
            changes.add(change("stockQuantity", String.valueOf(product.getStockQuantity()),
                    String.valueOf(request.stockQuantity())));
            product.changeStockQuantity(request.stockQuantity());
        }
        return new SellerProductUpdateResponse(productId, changes);
    }

    /** I-10 등록 — 등록은 change log 미기록 (04 §10), 카테고리는 소분류(leaf)만 (02 D26②) */
    @Transactional
    public Long create(Long brandId, SellerProductCreateRequest request) {
        int originalPrice = request.originalPrice() != null ? request.originalPrice() : request.price();
        validatePriceRange(request.price(), originalPrice);
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_CATEGORY_INVALID));
        if (category.isRoot()) {
            throw new BusinessException(ErrorCode.PRODUCT_CATEGORY_INVALID);
        }
        if (request.attributes() != null) {
            requireValidJson(request.attributes());
        }
        Product product = Product.create(brandId, request.categoryId(), request.name(), originalPrice,
                request.price(), request.stockQuantity(),
                request.imageUrl() != null ? request.imageUrl() : PLACEHOLDER_IMAGE,
                request.summary(), request.attributes(), sanitizeDescription(request.description()),
                request.status() != null ? request.status() : ProductStatus.ON_SALE);
        return productRepository.save(product).getId();
    }

    /** I-12 — soft delete(HIDDEN 전환)만, hard delete 문 없음 (05 §1-3). 재호출 멱등 */
    @Transactional
    public void softDelete(Long brandId, Long productId) {
        Product product = ownedProduct(brandId, productId);
        if (product.getStatus() == ProductStatus.HIDDEN) {
            return;
        }
        productChangeLogRepository.save(ProductChangeLog.of(productId, ProductChangeType.STATUS,
                product.getStatus().name(), ProductStatus.HIDDEN.name()));
        product.changeStatus(ProductStatus.HIDDEN);
    }

    /**
     * 소유권 검증 — productId는 LLM이 채우는 값이라 신뢰 불가, internal에서도 반복 (05 §I-9).
     * 쓰기 경로(수정·soft delete)라 비관적 락으로 로드 — 재고 절대값 수정이 주문 차감과 경합해도 lost update 없음 (02 D33).
     */
    private Product ownedProduct(Long brandId, Long productId) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        if (!product.getBrandId().equals(brandId)) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
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

    private static Sort toSort(String sort) {
        return switch (sort == null ? "latest" : sort) {
            case "price_asc" -> Sort.by(Sort.Direction.ASC, "price").and(Sort.by(Sort.Direction.DESC, "id"));
            case "price_desc" -> Sort.by(Sort.Direction.DESC, "price").and(Sort.by(Sort.Direction.DESC, "id"));
            case "latest" -> Sort.by(Sort.Direction.DESC, "id");
            default -> throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        };
    }

    private void validatePriceRange(int price, int originalPrice) {
        if (price > originalPrice) {
            throw new BusinessException(ErrorCode.PRODUCT_PRICE_INVALID);
        }
    }

    private void requireValidJson(String json) {
        try {
            objectMapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
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

    private void recordLog(Long productId, ProductChangeType type, int oldValue, int newValue) {
        productChangeLogRepository.save(ProductChangeLog.of(productId, type,
                String.valueOf(oldValue), String.valueOf(newValue)));
    }

    private static SellerProductUpdateResponse.Change change(String field, String before, String after) {
        return new SellerProductUpdateResponse.Change(field, before, after);
    }

    private static boolean changed(String requested, String current) {
        return requested != null && !Objects.equals(requested, current);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 서버측 XSS 최소 방어 (04 §7 S-5) — 실행 가능 태그·인라인 핸들러·javascript: 제거.
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
