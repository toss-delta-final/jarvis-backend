package com.jarvis.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.brand.BrandService;
import com.jarvis.category.CategoryService;
import com.jarvis.product.dto.ProductCardResponse;
import com.jarvis.review.ReviewService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;

/** M-7 최근 본 상품 — behavior_events product_view 재활용 (04 §5, 02 D3) */
@ExtendWith(MockitoExtension.class)
class ProductRecentServiceTest {

    private static final Long MEMBER_ID = 1L;

    @Mock ProductRepository productRepository;
    @Mock ProductOptionRepository productOptionRepository;
    @Mock BrandService brandService;
    @Mock CategoryService categoryService;
    @Mock ReviewService reviewService;
    @Mock ObjectMapper objectMapper;

    @InjectMocks ProductService productService;

    private Product product(Long id) {
        Product product = mock(Product.class, withSettings().strictness(Strictness.LENIENT));
        when(product.getId()).thenReturn(id);
        when(product.getBrandId()).thenReturn(1L);
        when(product.getName()).thenReturn("상품" + id);
        return product;
    }

    @Test
    @DisplayName("M-7 — 최근 본 순서를 보존해 카드로 조립 (findAllById 순서 뒤섞임 보정)")
    void recentPreservesViewOrder() {
        List<Product> shuffled = List.of(product(10L), product(20L), product(30L));
        when(productRepository.findRecentViewedIds(MEMBER_ID, 20)).thenReturn(List.of(30L, 10L, 20L));
        when(productRepository.findAllById(List.of(30L, 10L, 20L))).thenReturn(shuffled);
        lenient().when(reviewService.getStats(anyCollection())).thenReturn(Map.of());
        lenient().when(brandService.getNames(anyCollection())).thenReturn(Map.of(1L, "브랜드"));

        List<ProductCardResponse> cards = productService.getRecent(MEMBER_ID, 20);

        assertThat(cards).extracting(ProductCardResponse::productId).containsExactly(30L, 10L, 20L);
    }

    @Test
    @DisplayName("M-7 — 본 상품이 없으면 빈 목록 (조회 쿼리 생략)")
    void recentEmpty() {
        when(productRepository.findRecentViewedIds(MEMBER_ID, 20)).thenReturn(List.of());

        assertThat(productService.getRecent(MEMBER_ID, 20)).isEmpty();
    }
}
