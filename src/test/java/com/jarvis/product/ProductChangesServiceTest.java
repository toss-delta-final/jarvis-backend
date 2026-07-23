package com.jarvis.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.brand.BrandService;
import com.jarvis.category.CategoryService;
import com.jarvis.product.dto.ProductChangesResponse;
import com.jarvis.review.ReviewService;
import com.jarvis.review.dto.RatingStats;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;

/** I-17 상품 변경분 배치 pull — 커서/hasMore/HIDDEN 최소필드 (05 §I-17) */
@ExtendWith(MockitoExtension.class)
class ProductChangesServiceTest {

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 1, 30, 12, 0, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 1, 30, 12, 1, 0);
    private static final LocalDateTime T3 = LocalDateTime.of(2026, 1, 30, 12, 2, 0);

    @Mock ProductRepository productRepository;
    @Mock ProductOptionRepository productOptionRepository;
    @Mock BrandService brandService;
    @Mock CategoryService categoryService;
    @Mock ReviewService reviewService;
    @Mock ObjectMapper objectMapper;

    @InjectMocks ProductService productService;

    @BeforeEach
    void stubLookups() {
        lenient().when(categoryService.getNames(anyCollection())).thenReturn(Map.of(10L, "카테고리"));
        lenient().when(brandService.getNames(anyCollection())).thenReturn(Map.of(20L, "브랜드"));
        lenient().when(reviewService.getStats(anyCollection()))
                .thenReturn(Map.of(1L, new RatingStats(82, 4.6)));
    }

    @Test
    @DisplayName("페이지가 꽉 차면 hasMore=true, nextCursor는 마지막 반환 항목 커서(초과분 드롭)")
    void hasMoreTrimsAndEncodesCursor() {
        List<Product> rows = List.of(onSale(1L, T1), onSale(2L, T2), onSale(3L, T3)); // limit 2 + 1
        when(productRepository.findChangesSince(any(), any(), any())).thenReturn(rows);

        ProductChangesResponse res = productService.getChanges("0", 2);

        assertThat(res.hasMore()).isTrue();
        assertThat(res.items()).extracting(ProductChangesResponse.Item::productId).containsExactly(1L, 2L);
        assertThat(ProductChangeCursor.decode(res.nextCursor()).id()).isEqualTo(2L); // 3L은 다음 페이지
    }

    @Test
    @DisplayName("마지막 페이지는 hasMore=false, nextCursor는 마지막 항목 커서")
    void lastPage() {
        List<Product> rows = List.of(onSale(1L, T1), onSale(2L, T2));
        when(productRepository.findChangesSince(any(), any(), any())).thenReturn(rows);

        ProductChangesResponse res = productService.getChanges("0", 5);

        assertThat(res.hasMore()).isFalse();
        assertThat(res.items()).hasSize(2);
        assertThat(ProductChangeCursor.decode(res.nextCursor()).id()).isEqualTo(2L);
    }

    @Test
    @DisplayName("변경분 없으면 items=[]·hasMore=false·nextCursor는 요청 since 그대로 echo")
    void emptyEchoesSince() {
        when(productRepository.findChangesSince(any(), any(), any())).thenReturn(List.of());

        ProductChangesResponse res = productService.getChanges("0", 500);

        assertThat(res.items()).isEmpty();
        assertThat(res.hasMore()).isFalse();
        assertThat(res.nextCursor()).isEqualTo("0");
    }

    @Test
    @DisplayName("HIDDEN은 productId·status·updatedAt만, ON_SALE은 평점·리뷰수 포함")
    void hiddenCarriesMinimalFields() {
        List<Product> rows = List.of(onSale(1L, T1), hidden(2L, T2));
        when(productRepository.findChangesSince(any(), any(), any())).thenReturn(rows);

        List<ProductChangesResponse.Item> items = productService.getChanges("0", 500).items();

        ProductChangesResponse.Item onSale = items.get(0);
        assertThat(onSale.status()).isEqualTo("ON_SALE");
        assertThat(onSale.name()).isEqualTo("상품1");
        assertThat(onSale.rating()).isEqualTo(4.6);
        assertThat(onSale.reviewCount()).isEqualTo(82L);

        ProductChangesResponse.Item hidden = items.get(1);
        assertThat(hidden.status()).isEqualTo("HIDDEN");
        assertThat(hidden.name()).isNull();
        assertThat(hidden.price()).isNull();
        assertThat(hidden.rating()).isNull();
        assertThat(hidden.updatedAt()).isNotNull();
    }

    private Product onSale(long id, LocalDateTime updatedAt) {
        Product p = mock(Product.class, withSettings().strictness(Strictness.LENIENT));
        when(p.getId()).thenReturn(id);
        when(p.getStatus()).thenReturn(ProductStatus.ON_SALE);
        when(p.getUpdatedAt()).thenReturn(updatedAt);
        when(p.getName()).thenReturn("상품" + id);
        when(p.getPrice()).thenReturn(1000);
        when(p.getCategoryId()).thenReturn(10L);
        when(p.getBrandId()).thenReturn(20L);
        when(p.getAttributes()).thenReturn(null); // parseJson이 objectMapper 안 타게
        return p;
    }

    private Product hidden(long id, LocalDateTime updatedAt) {
        Product p = mock(Product.class, withSettings().strictness(Strictness.LENIENT));
        when(p.getId()).thenReturn(id);
        when(p.getStatus()).thenReturn(ProductStatus.HIDDEN);
        when(p.getUpdatedAt()).thenReturn(updatedAt);
        return p;
    }
}
