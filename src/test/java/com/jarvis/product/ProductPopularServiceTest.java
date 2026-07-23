package com.jarvis.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.brand.BrandService;
import com.jarvis.category.CategoryService;
import com.jarvis.product.dto.ProductCandidateResponse;
import com.jarvis.product.dto.ProductCardResponse;
import com.jarvis.review.ReviewService;
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

/** P-4 인기 상품 + I-3 인기 후보 — 7일 판매수 → product_view 수 → 최신순 채움 (04 §2, 05 §I-3) */
@ExtendWith(MockitoExtension.class)
class ProductPopularServiceTest {

    @Mock ProductRepository productRepository;
    @Mock ProductOptionRepository productOptionRepository;
    @Mock BrandService brandService;
    @Mock CategoryService categoryService;
    @Mock ReviewService reviewService;
    @Mock ObjectMapper objectMapper;

    @InjectMocks ProductService productService;

    @BeforeEach
    void stubLookups() {
        lenient().when(reviewService.getStats(anyCollection())).thenReturn(Map.of());
        lenient().when(brandService.getNames(anyCollection())).thenReturn(Map.of(20L, "브랜드"));
        lenient().when(categoryService.getNames(anyCollection())).thenReturn(Map.of(10L, "티셔츠"));
    }

    private Product product(Long id) {
        Product product = mock(Product.class, withSettings().strictness(Strictness.LENIENT));
        when(product.getId()).thenReturn(id);
        when(product.getBrandId()).thenReturn(20L);
        when(product.getCategoryId()).thenReturn(10L);
        when(product.getName()).thenReturn("상품" + id);
        when(product.getSummary()).thenReturn("요약" + id);
        return product;
    }

    @Test
    @DisplayName("P-4 — 판매수만으로 size가 차면 조회수·최신순 fallback 쿼리 생략, 판매순 보존")
    void salesFillsAllSkipsFallbacks() {
        List<Product> shuffled = List.of(product(10L), product(20L), product(30L)); // findAllById는 순서 미보장
        when(productRepository.findPopularIdsBySales(any(), eq(3))).thenReturn(List.of(30L, 10L, 20L));
        when(productRepository.findAllById(List.of(30L, 10L, 20L))).thenReturn(shuffled);

        List<ProductCardResponse> cards = productService.getPopular(3);

        assertThat(cards).extracting(ProductCardResponse::productId).containsExactly(30L, 10L, 20L);
        verify(productRepository, never()).findPopularIdsByViews(any(), anyList(), anyInt());
        verify(productRepository, never()).findLatestIds(anyList(), anyInt());
    }

    @Test
    @DisplayName("P-4 — 판매수 부족분은 조회수 → 최신순으로 채움 (앞 단계 id 제외)")
    void fallbackFillsWithExclusions() {
        List<Product> products = List.of(product(1L), product(2L), product(3L), product(4L));
        when(productRepository.findPopularIdsBySales(any(), eq(4))).thenReturn(List.of(1L));
        when(productRepository.findPopularIdsByViews(any(), eq(List.of(1L)), eq(3)))
                .thenReturn(List.of(2L));
        when(productRepository.findLatestIds(eq(List.of(1L, 2L)), eq(2))).thenReturn(List.of(3L, 4L));
        when(productRepository.findAllById(List.of(1L, 2L, 3L, 4L))).thenReturn(products);

        List<ProductCardResponse> cards = productService.getPopular(4);

        assertThat(cards).extracting(ProductCardResponse::productId)
                .containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    @DisplayName("P-4 — 판매·조회 이력이 전혀 없으면 최신순만으로 채움 (NOT IN 센티널 -1)")
    void latestOnlyWithSentinelExclusion() {
        when(productRepository.findPopularIdsBySales(any(), eq(2))).thenReturn(List.of());
        when(productRepository.findPopularIdsByViews(any(), eq(List.of(-1L)), eq(2)))
                .thenReturn(List.of());
        List<Product> products = List.of(product(5L), product(6L));
        when(productRepository.findLatestIds(eq(List.of(-1L)), eq(2))).thenReturn(List.of(5L, 6L));
        when(productRepository.findAllById(List.of(5L, 6L))).thenReturn(products);

        List<ProductCardResponse> cards = productService.getPopular(2);

        assertThat(cards).extracting(ProductCardResponse::productId).containsExactly(5L, 6L);
    }

    @Test
    @DisplayName("P-4 — 인기 id 중 실체가 조회되지 않는 상품은 드롭 (null 카드 방지)")
    void missingProductDropped() {
        List<Product> products = List.of(product(1L), product(3L)); // 2L 실체 없음
        when(productRepository.findPopularIdsBySales(any(), eq(3))).thenReturn(List.of(1L, 2L, 3L));
        when(productRepository.findAllById(List.of(1L, 2L, 3L))).thenReturn(products);

        List<ProductCardResponse> cards = productService.getPopular(3);

        assertThat(cards).extracting(ProductCardResponse::productId).containsExactly(1L, 3L);
    }

    @Test
    @DisplayName("I-3 — 인기 상품을 리랭킹용 최소필드 후보로 매핑 (응답 형식 I-1과 동일, 05 §I-3)")
    void popularCandidatesMapsMinimalFields() {
        List<Product> products = List.of(product(1L), product(2L));
        when(productRepository.findPopularIdsBySales(any(), eq(2))).thenReturn(List.of(2L, 1L));
        when(productRepository.findAllById(List.of(2L, 1L))).thenReturn(products);

        List<ProductCandidateResponse> result = productService.getPopularCandidates(2);

        assertThat(result).extracting(ProductCandidateResponse::productId).containsExactly(2L, 1L);
        assertThat(result.get(0).name()).isEqualTo("상품2");
        assertThat(result.get(0).summary()).isEqualTo("요약2");
        assertThat(result.get(0).categoryName()).isEqualTo("티셔츠");
        assertThat(result.get(0).brandName()).isEqualTo("브랜드");
        assertThat(result.get(0).attributes()).isNull(); // attributes 없는 상품은 null 유지
    }
}
