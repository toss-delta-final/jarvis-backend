package com.jarvis.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.jarvis.seller.dto.SellerProductUpdateRequest;
import com.jarvis.seller.dto.SellerProductUpdateResponse;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** S-5/I-11 공용 수정 — 검증·change log 규칙 (04 §7·§10, 02 D28·D32) */
@ExtendWith(MockitoExtension.class)
class SellerProductServiceTest {

    private static final Long BRAND_ID = 7L;

    @Mock private ProductRepository productRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private ProductChangeLogRepository productChangeLogRepository;

    private SellerProductService service;

    @BeforeEach
    void setUp() {
        service = new SellerProductService(productRepository, orderItemRepository,
                categoryRepository, productChangeLogRepository, new ObjectMapper());
    }

    private Product ownProduct() {
        return Product.create(BRAND_ID, 20L, "에어프라이어", 129000, 96800, 100,
                "/img.webp", "요약", null, "설명", ProductStatus.ON_SALE);
    }

    @Test
    @DisplayName("price·stock·status 변경은 change log 기록, 그 외 필드는 changes[]만 (02 D32 어휘 3종)")
    void updateRecordsLogsOnlyForVocabularyFields() {
        Product product = ownProduct();
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        SellerProductUpdateRequest request = new SellerProductUpdateRequest(
                "새 이름", null, null, null, 89000, null, null, 50);

        SellerProductUpdateResponse response = service.update(BRAND_ID, 1L, request);

        assertThat(response.changes()).extracting(SellerProductUpdateResponse.Change::field)
                .containsExactlyInAnyOrder("name", "price", "stockQuantity");
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
        SellerProductUpdateRequest request = new SellerProductUpdateRequest(
                "에어프라이어", null, null, null, 96800, null, ProductStatus.ON_SALE, 100);

        SellerProductUpdateResponse response = service.update(BRAND_ID, 1L, request);

        assertThat(response.changes()).isEmpty();
        verify(productChangeLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("price > originalPrice면 400 PRODUCT_PRICE_INVALID (02 D28) — 교차 필드까지 검증")
    void updateRejectsPriceOverOriginal() {
        Product product = ownProduct();
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        SellerProductUpdateRequest request = new SellerProductUpdateRequest(
                null, null, null, null, 150000, null, null, null);

        assertThatThrownBy(() -> service.update(BRAND_ID, 1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRODUCT_PRICE_INVALID);
    }

    @Test
    @DisplayName("타 브랜드 상품이면 403 — productId는 LLM 값이라 internal에서도 재검증 (05 §I-9)")
    void updateRejectsOtherBrandProduct() {
        Product product = ownProduct();
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.update(999L, 1L,
                new SellerProductUpdateRequest("이름", null, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_FORBIDDEN);
    }

    @Test
    @DisplayName("I-12 soft delete — HIDDEN 전환 + STATUS 로그, 재호출 멱등 (05 §1-3)")
    void softDeleteIsIdempotent() {
        Product product = ownProduct();
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));

        service.softDelete(BRAND_ID, 1L);
        assertThat(product.getStatus()).isEqualTo(ProductStatus.HIDDEN);
        verify(productChangeLogRepository).save(any());

        service.softDelete(BRAND_ID, 1L); // 재confirm — 추가 로그 없음
        verify(productChangeLogRepository, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    @DisplayName("I-10 등록 — 대분류 카테고리면 400 PRODUCT_CATEGORY_INVALID (02 D26②)")
    void createRejectsRootCategory() {
        Category root = org.mockito.Mockito.mock(Category.class);
        when(root.isRoot()).thenReturn(true);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(root));

        assertThatThrownBy(() -> service.create(BRAND_ID,
                new com.jarvis.seller.dto.SellerProductCreateRequest(
                        "새 상품", 10000, null, 100, 1L, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRODUCT_CATEGORY_INVALID);
    }

    @Test
    @DisplayName("description sanitize — script/인라인 핸들러/javascript: 제거 (04 §7 S-5)")
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
        assertThatThrownBy(() -> service.update(BRAND_ID, 1L,
                new SellerProductUpdateRequest(null, null, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }
}
