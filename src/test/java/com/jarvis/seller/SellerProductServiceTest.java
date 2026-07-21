package com.jarvis.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.brand.BrandRepository;
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
import com.jarvis.seller.dto.SellerProductCreateResponse;
import com.jarvis.seller.dto.SellerProductDeleteResponse;
import com.jarvis.seller.dto.SellerProductInternalListResponse;
import com.jarvis.seller.dto.SellerProductListResponse;
import com.jarvis.seller.dto.SellerProductUpdateRequest;
import com.jarvis.seller.dto.SellerProductUpdateResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** I-11 상품 수정 — 검증·change log 규칙 (04 §7·§10, 02 D28·D32, 노션 I-9~I-12) */
@ExtendWith(MockitoExtension.class)
class SellerProductServiceTest {

    private static final Long BRAND_ID = 7L;

    @Mock private ProductRepository productRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private ProductChangeLogRepository productChangeLogRepository;
    @Mock private BrandRepository brandRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SellerProductService service;

    @BeforeEach
    void setUp() {
        service = new SellerProductService(productRepository, orderItemRepository,
                categoryRepository, productChangeLogRepository, brandRepository, objectMapper);
    }

    private Product ownProduct() {
        return Product.create(BRAND_ID, 20L, "에어프라이어", 129000, 96800, 100,
                "/img.webp", "요약", null, "설명", ProductStatus.ON_SALE);
    }

    private static SellerProductUpdateRequest updateRequest(String name, JsonNode attributes,
                                                            Integer price, ProductStatus status,
                                                            Integer stockQuantity, String imageUrl) {
        return new SellerProductUpdateRequest(name, null, attributes, null, price, null,
                status, stockQuantity, imageUrl);
    }

    @Test
    @DisplayName("price·stock 변경은 change log + changes 어휘(PRICE/STOCK), 그 외 필드는 changes 미포함 (노션 I-11)")
    void updateRecordsLogsOnlyForVocabularyFields() {
        Product product = ownProduct();
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        SellerProductUpdateRequest request = updateRequest("새 이름", null, 89000, null, 50, null);

        SellerProductUpdateResponse response = service.updateInternal(BRAND_ID, 1L, request);

        assertThat(response.changes()).containsExactlyInAnyOrder("PRICE", "STOCK");
        assertThat(response.productId()).isEqualTo(1L);
        assertThat(response.price()).isEqualTo(89000);
        assertThat(response.stockQuantity()).isEqualTo(50);
        assertThat(response.status()).isEqualTo("ON_SALE");
        ArgumentCaptor<ProductChangeLog> captor = ArgumentCaptor.forClass(ProductChangeLog.class);
        verify(productChangeLogRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(ProductChangeLog::getChangeType)
                .containsExactlyInAnyOrder(ProductChangeType.PRICE, ProductChangeType.STOCK);
        assertThat(product.getName()).isEqualTo("새 이름");
        assertThat(product.getPrice()).isEqualTo(89000);
        assertThat(product.getStockQuantity()).isEqualTo(50);
    }

    @Test
    @DisplayName("동일 값 요청은 미기록·changes 빈 배열 (02 D32 '전후 동일 시 미기록')")
    void updateSameValueRecordsNothing() {
        Product product = ownProduct();
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        SellerProductUpdateRequest request = updateRequest(
                "에어프라이어", null, 96800, ProductStatus.ON_SALE, 100, null);

        SellerProductUpdateResponse response = service.updateInternal(BRAND_ID, 1L, request);

        assertThat(response.changes()).isEmpty();
        verify(productChangeLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("attributes는 JSON 객체로 받아 문자열 저장, imageUrl은 로그·changes 없이 반영 (노션 I-10·I-11)")
    void updateSerializesAttributesAndAppliesImageUrl() throws Exception {
        Product product = ownProduct();
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        JsonNode attributes = objectMapper.readTree("{\"size\":[\"250\",\"260\"]}");

        SellerProductUpdateResponse response = service.updateInternal(BRAND_ID, 1L,
                updateRequest(null, attributes, null, null, null, "/new.webp"));

        assertThat(product.getAttributes()).isEqualTo("{\"size\":[\"250\",\"260\"]}");
        assertThat(product.getImageUrl()).isEqualTo("/new.webp");
        assertThat(response.changes()).isEmpty();
        verify(productChangeLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("price > originalPrice면 422 INVALID_PRICE (02 D28, 노션 I-11) — 교차 필드까지 검증")
    void updateRejectsPriceOverOriginal() {
        Product product = ownProduct();
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        SellerProductUpdateRequest request = updateRequest(null, null, 150000, null, null, null);

        assertThatThrownBy(() -> service.updateInternal(BRAND_ID, 1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PRICE);
    }

    @Test
    @DisplayName("stockQuantity < 0이면 422 INVALID_STOCK (노션 I-11)")
    void updateRejectsNegativeStock() {
        Product product = ownProduct();
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.updateInternal(BRAND_ID, 1L,
                updateRequest(null, null, null, null, -1, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_STOCK);
    }

    @Test
    @DisplayName("internal 경로(I-11) — 타 브랜드 상품이면 404 PRODUCT_NOT_FOUND(존재 은닉 — 노션 I-11)")
    void updateInternalHidesOtherBrandProduct() {
        Product product = ownProduct();
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.updateInternal(999L, 1L,
                updateRequest("이름", null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("I-12 soft delete — HIDDEN 전환 + STATUS 로그, {productId, HIDDEN} 응답 (노션 I-12)")
    void softDeleteHidesAndLogsStatus() {
        Product product = ownProduct();
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));

        SellerProductDeleteResponse response = service.softDelete(BRAND_ID, 1L);

        assertThat(product.getStatus()).isEqualTo(ProductStatus.HIDDEN);
        assertThat(response.productId()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo("HIDDEN");
        verify(productChangeLogRepository).save(any());
    }

    @Test
    @DisplayName("I-12 — 이미 HIDDEN이면 409 ALREADY_HIDDEN (노션 계약, 2026-07-18 결정)")
    void softDeleteRejectsAlreadyHidden() {
        Product product = ownProduct();
        product.changeStatus(ProductStatus.HIDDEN);
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.softDelete(BRAND_ID, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_HIDDEN);
        verify(productChangeLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("I-12 — 타 브랜드 상품이면 404 PRODUCT_NOT_FOUND(존재 은닉 — I-11과 동일)")
    void softDeleteHidesOtherBrandProduct() {
        Product product = ownProduct();
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.softDelete(999L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("I-10 등록 — {productId, status} 반환 (노션 I-10, 201은 컨트롤러 소관)")
    void createReturnsProductIdAndStatus() {
        Category leaf = mock(Category.class);
        when(leaf.isRoot()).thenReturn(false);
        when(categoryRepository.findById(20L)).thenReturn(Optional.of(leaf));
        Product saved = mock(Product.class);
        when(saved.getId()).thenReturn(205L);
        when(saved.getStatus()).thenReturn(ProductStatus.ON_SALE);
        when(productRepository.save(any())).thenReturn(saved);

        SellerProductCreateResponse response = service.create(BRAND_ID,
                new SellerProductCreateRequest("새 상품", 10000, null, 100, 20L,
                        null, null, null, null, null));

        assertThat(response.productId()).isEqualTo(205L);
        assertThat(response.status()).isEqualTo("ON_SALE");
        verify(productChangeLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("I-10 등록 — 필수값 누락은 422 MISSING_FIELD, 메시지에 필드명 명시 (노션 I-10)")
    void createRejectsMissingFields() {
        assertThatThrownBy(() -> service.create(BRAND_ID,
                new SellerProductCreateRequest("새 상품", 10000, null, 100, null,
                        null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("categoryId")
                .extracting("errorCode").isEqualTo(ErrorCode.MISSING_FIELD);

        assertThatThrownBy(() -> service.create(BRAND_ID,
                new SellerProductCreateRequest(null, null, null, null, 20L,
                        null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("name")
                .hasMessageContaining("price")
                .hasMessageContaining("stockQuantity")
                .extracting("errorCode").isEqualTo(ErrorCode.MISSING_FIELD);
    }

    @Test
    @DisplayName("I-10 등록 — price > originalPrice면 422 INVALID_PRICE, stock < 0이면 422 INVALID_STOCK")
    void createRejectsInvalidPriceAndStock() {
        assertThatThrownBy(() -> service.create(BRAND_ID,
                new SellerProductCreateRequest("새 상품", 20000, 10000, 100, 20L,
                        null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PRICE);

        assertThatThrownBy(() -> service.create(BRAND_ID,
                new SellerProductCreateRequest("새 상품", 10000, null, -1, 20L,
                        null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_STOCK);
    }

    @Test
    @DisplayName("I-10 등록 — 대분류 카테고리면 400 PRODUCT_CATEGORY_INVALID (02 D26②)")
    void createRejectsRootCategory() {
        Category root = mock(Category.class);
        when(root.isRoot()).thenReturn(true);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(root));

        assertThatThrownBy(() -> service.create(BRAND_ID,
                new SellerProductCreateRequest("새 상품", 10000, null, 100, 1L,
                        null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRODUCT_CATEGORY_INVALID);
    }

    @Test
    @DisplayName("I-9 — offset은 페이지 그리드 스냅 없이 진짜 row offset, 응답은 {rows, total} (노션 I-9)")
    void listInternalUsesTrueOffsetAndWrapsRows() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        Product product = ownProduct();
        // PageImpl은 offset+size > total이면 total을 재계산하므로 size 1로 고정해 total 17 유지
        Page<Product> page = new PageImpl<>(List.of(product), PageRequest.of(0, 1), 17);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(productRepository.findSellerProducts(eq(BRAND_ID), isNull(), isNull(),
                pageableCaptor.capture())).thenReturn(page);
        when(orderItemRepository.sumPaidQuantityByProduct(any())).thenReturn(List.of());
        Category leaf = mock(Category.class);
        when(leaf.getId()).thenReturn(20L);
        when(leaf.getName()).thenReturn("신발");
        when(categoryRepository.findAllById(any())).thenReturn(List.of(leaf));

        SellerProductInternalListResponse response = service.listInternal(BRAND_ID, null, null, 20, 30);

        assertThat(pageableCaptor.getValue().getOffset()).isEqualTo(30);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
        assertThat(response.total()).isEqualTo(17);
        assertThat(response.rows()).hasSize(1);
        assertThat(response.rows().getFirst().category()).isEqualTo("신발");
        assertThat(response.rows().getFirst().imageUrl()).isEqualTo("/img.webp");
    }

    @Test
    @DisplayName("I-9 — brandId 미존재면 404 BRAND_NOT_FOUND (노션 I-9)")
    void listInternalRejectsUnknownBrand() {
        when(brandRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.listInternal(99L, null, null, 20, 0))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BRAND_NOT_FOUND);
    }

    @Test
    @DisplayName("description sanitize — script/인라인 핸들러/javascript: 제거 (04 §10 I-11)")
    void sanitizeStripsExecutableHtml() {
        String dirty = "<p onclick=\"x()\">좋은 상품</p><script>alert(1)</script>"
                + "<a href=\"javascript:evil()\">링크</a>";

        String clean = SellerProductService.sanitizeDescription(dirty);

        assertThat(clean).doesNotContain("<script>").doesNotContain("onclick")
                .doesNotContain("javascript:").contains("좋은 상품");
    }

    @Test
    @DisplayName("전 필드 null 요청은 400 (부분 수정이라도 최소 1개 필드)")
    void updateRejectsEmptyRequest() {
        assertThatThrownBy(() -> service.updateInternal(BRAND_ID, 1L,
                new SellerProductUpdateRequest(null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    /** S-3 목록 테스트용 — 단위 테스트라 id를 명시(정렬 tiebreak)·나머지 필드는 lenient */
    private static Product product(long id, ProductStatus status, int stock, int price, long categoryId) {
        Product p = mock(Product.class);
        lenient().when(p.getId()).thenReturn(id);
        lenient().when(p.getStatus()).thenReturn(status);
        lenient().when(p.getStockQuantity()).thenReturn(stock);
        lenient().when(p.getPrice()).thenReturn(price);
        lenient().when(p.getOriginalPrice()).thenReturn(price);
        lenient().when(p.getBaseSalesCount()).thenReturn(0);
        lenient().when(p.getCategoryId()).thenReturn(categoryId);
        lenient().when(p.getName()).thenReturn("상품" + id);
        lenient().when(p.getImageUrl()).thenReturn("/img" + id + ".webp");
        return p;
    }

    @Test
    @DisplayName("S-3 목록 — displayStatus 파생(ON_SALE/SOLD_OUT/HIDDEN) + tabCounts 전량 기준 (노션 S-3)")
    void listDerivesDisplayStatusAndTabCounts() {
        Product onSale = product(1L, ProductStatus.ON_SALE, 5, 10000, 20L);
        Product soldOut = product(2L, ProductStatus.ON_SALE, 0, 20000, 20L);
        Product hidden = product(3L, ProductStatus.HIDDEN, 0, 30000, 20L);
        when(productRepository.findAllByBrandId(BRAND_ID)).thenReturn(List.of(onSale, soldOut, hidden));
        when(orderItemRepository.sumPaidQuantityByProduct(any())).thenReturn(List.of());
        Category leaf = mock(Category.class);
        lenient().when(leaf.getId()).thenReturn(20L);
        lenient().when(leaf.getName()).thenReturn("신발");
        when(categoryRepository.findAllById(any())).thenReturn(List.of(leaf));

        SellerProductListResponse res = service.list(BRAND_ID, null, "latest", 0, 20);

        assertThat(res.tabCounts()).containsEntry("ALL", 3L).containsEntry("ON_SALE", 1L)
                .containsEntry("SOLD_OUT", 1L).containsEntry("HIDDEN", 1L);
        assertThat(res.content()).extracting(SellerProductListResponse.Row::productId)
                .containsExactly(3L, 2L, 1L); // latest = id desc
        assertThat(res.content()).extracting(SellerProductListResponse.Row::displayStatus)
                .containsExactly("HIDDEN", "SOLD_OUT", "ON_SALE");
        assertThat(res.content().get(1).status()).isEqualTo("ON_SALE"); // 원본 status는 SOLD_OUT 파생과 별개
        assertThat(res.totalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("S-3 목록 — status=SOLD_OUT 필터는 ON_SALE·재고 0만 (displayStatus 기준, 노션 S-3)")
    void listFiltersByDisplayStatus() {
        Product onSale = product(1L, ProductStatus.ON_SALE, 5, 10000, 20L);
        Product soldOut = product(2L, ProductStatus.ON_SALE, 0, 20000, 20L);
        Product hidden = product(3L, ProductStatus.HIDDEN, 0, 30000, 20L);
        when(productRepository.findAllByBrandId(BRAND_ID)).thenReturn(List.of(onSale, soldOut, hidden));
        when(orderItemRepository.sumPaidQuantityByProduct(any())).thenReturn(List.of());
        when(categoryRepository.findAllById(any())).thenReturn(List.of());

        SellerProductListResponse res = service.list(BRAND_ID, "SOLD_OUT", "latest", 0, 20);

        assertThat(res.content()).extracting(SellerProductListResponse.Row::productId).containsExactly(2L);
        assertThat(res.tabCounts()).containsEntry("ALL", 3L); // 카운트는 필터 무관 전량
    }

    @Test
    @DisplayName("S-3 목록 — 잘못된 status/sort/size는 400 PRODUCT_INVALID_PARAM (노션 S-3)")
    void listRejectsInvalidParams() {
        assertThatThrownBy(() -> service.list(BRAND_ID, "BOGUS", "latest", 0, 20))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRODUCT_INVALID_PARAM);
        assertThatThrownBy(() -> service.list(BRAND_ID, null, "unknown", 0, 20))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRODUCT_INVALID_PARAM);
        assertThatThrownBy(() -> service.list(BRAND_ID, null, "latest", 0, 101))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRODUCT_INVALID_PARAM);
    }
}
