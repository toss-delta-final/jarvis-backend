package com.jarvis.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.brand.BrandService;
import com.jarvis.category.CategoryService;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.product.dto.ProductCandidateResponse;
import com.jarvis.review.ReviewService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;

/** I-1 후보 조회 + P-7 공개 카드 (05 §I-1, 04 §2) */
@ExtendWith(MockitoExtension.class)
class ProductCandidateSearchTest {

    @Mock ProductRepository productRepository;
    @Mock ProductOptionRepository productOptionRepository;
    @Mock BrandService brandService;
    @Mock CategoryService categoryService;
    @Mock ReviewService reviewService;
    @Mock ObjectMapper objectMapper;

    @InjectMocks ProductService productService;

    @Test
    @DisplayName("I-1 — 미존재 카테고리명이면 후보 0건 (쿼리 생략)")
    void unknownCategoryReturnsEmpty() {
        when(categoryService.resolveIdsByName("없는분류")).thenReturn(Optional.empty());

        List<ProductCandidateResponse> result = productService.searchCandidates(
                null, "없는분류", null, null, null, null, 50);

        assertThat(result).isEmpty();
        verifyNoInteractions(productRepository);
    }

    @Test
    @DisplayName("I-1 — 미존재 브랜드명이면 후보 0건 (쿼리 생략)")
    void unknownBrandReturnsEmpty() {
        when(brandService.findIdByName("없는브랜드")).thenReturn(Optional.empty());

        List<ProductCandidateResponse> result = productService.searchCandidates(
                null, null, null, null, "없는브랜드", null, 50);

        assertThat(result).isEmpty();
        verifyNoInteractions(productRepository);
    }

    @Test
    @DisplayName("I-1 — size는 최대 200으로 클램프 (라운드1 LIMIT, 05 §I-1)")
    void sizeClampedTo200() {
        when(productRepository.searchCandidates(any(), eq(false), any(), any(), any(), any(), any(),
                eq(PageRequest.of(0, 200)))).thenReturn(List.of());

        productService.searchCandidates(null, null, null, null, null, null, 500);
    }

    @Test
    @DisplayName("I-1 — 대분류명이면 하위 소분류 전체 포함 검색 (02 D20)")
    void rootCategoryIncludesChildren() {
        when(categoryService.resolveIdsByName("패션")).thenReturn(Optional.of(List.of(11L, 12L)));
        Product product = mock(Product.class, withSettings().strictness(Strictness.LENIENT));
        when(product.getId()).thenReturn(1L);
        when(product.getCategoryId()).thenReturn(11L);
        when(product.getBrandId()).thenReturn(2L);
        when(productRepository.searchCandidates(any(), eq(true), eq(List.of(11L, 12L)),
                any(), any(), any(), any(), any())).thenReturn(List.of(product));
        lenient().when(categoryService.getNames(anyCollection())).thenReturn(Map.of(11L, "티셔츠"));
        lenient().when(brandService.getNames(anyCollection())).thenReturn(Map.of(2L, "브랜드"));

        List<ProductCandidateResponse> result = productService.searchCandidates(
                null, "패션", null, null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).categoryName()).isEqualTo("티셔츠");
        assertThat(result.get(0).brandName()).isEqualTo("브랜드");
    }

    @Test
    @DisplayName("P-7 — ids 상한 20 초과·빈 목록은 400")
    void publicCardsValidatesIds() {
        assertThatThrownBy(() -> productService.getPublicCards(
                LongStream.rangeClosed(1, 21).boxed().toList()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThatThrownBy(() -> productService.getPublicCards(List.of()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }
}
