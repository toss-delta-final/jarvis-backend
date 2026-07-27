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
import com.jarvis.product.dto.CandidateRow;
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

    private static Product product(long id, long categoryId, long brandId, int price) {
        Product product = mock(Product.class, withSettings().strictness(Strictness.LENIENT));
        when(product.getId()).thenReturn(id);
        when(product.getCategoryId()).thenReturn(categoryId);
        when(product.getBrandId()).thenReturn(brandId);
        when(product.getPrice()).thenReturn(price);
        return product;
    }

    @Test
    @DisplayName("I-1 — 미존재 카테고리명이면 후보 0건 (쿼리 생략)")
    void unknownCategoryReturnsEmpty() {
        when(categoryService.resolveIdsByName("없는분류")).thenReturn(Optional.empty());

        List<ProductCandidateResponse> result = productService.searchCandidates(
                null, "없는분류", null, null, null, null);

        assertThat(result).isEmpty();
        verifyNoInteractions(productRepository);
    }

    @Test
    @DisplayName("I-1 — brandName이 전부 미존재면 후보 0건 (쿼리 생략)")
    void allUnknownBrandsReturnEmpty() {
        when(brandService.findIdByName("없는브랜드")).thenReturn(Optional.empty());

        List<ProductCandidateResponse> result = productService.searchCandidates(
                null, null, null, null, List.of("없는브랜드"), null);

        assertThat(result).isEmpty();
        verifyNoInteractions(productRepository);
    }

    @Test
    @DisplayName("I-1 — brandName 리스트 중 존재하는 브랜드만으로 검색한다")
    void partiallyKnownBrandsSearchRemaining() {
        when(brandService.findIdByName("나이키")).thenReturn(Optional.of(7L));
        when(brandService.findIdByName("없는브랜드")).thenReturn(Optional.empty());
        when(productRepository.searchCandidates(any(), eq(false), any(), eq(true), eq(List.of(7L)),
                any(), any(), any())).thenReturn(List.of());

        List<ProductCandidateResponse> result = productService.searchCandidates(
                null, null, null, null, List.of("나이키", "없는브랜드"), null);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("I-1 — 후보 수 상한이 없다: 매칭된 행을 전부 반환 (2026-07-27 개정)")
    void returnsEveryMatchWithoutLimit() {
        List<CandidateRow> rows = LongStream.rangeClosed(1, 300)
                .mapToObj(id -> new CandidateRow(product(id, 11L, 2L, 1000), 0L, null))
                .toList();
        when(productRepository.searchCandidates(any(), eq(false), any(), eq(false), any(),
                any(), any(), any())).thenReturn(rows);
        lenient().when(categoryService.getNames(anyCollection())).thenReturn(Map.of(11L, "티셔츠"));
        lenient().when(brandService.getNames(anyCollection())).thenReturn(Map.of(2L, "브랜드"));

        List<ProductCandidateResponse> result = productService.searchCandidates(
                null, null, null, null, null, null);

        assertThat(result).hasSize(300);
    }

    @Test
    @DisplayName("I-1 — 응답에 price·rating·reviewCount를 싣는다 (리랭킹 계산 입력)")
    void includesRerankInputs() {
        CandidateRow row = new CandidateRow(product(1L, 11L, 2L, 29900), 15L, 4.75);
        when(productRepository.searchCandidates(any(), eq(false), any(), eq(false), any(),
                any(), any(), any())).thenReturn(List.of(row));
        lenient().when(categoryService.getNames(anyCollection())).thenReturn(Map.of(11L, "티셔츠"));
        lenient().when(brandService.getNames(anyCollection())).thenReturn(Map.of(2L, "브랜드"));

        ProductCandidateResponse candidate = productService.searchCandidates(
                null, null, null, null, null, null).get(0);

        assertThat(candidate.price()).isEqualTo(29900);
        assertThat(candidate.rating()).isEqualTo(4.8); // 소수 1자리 반올림 (RatingStats)
        assertThat(candidate.reviewCount()).isEqualTo(15);
    }

    @Test
    @DisplayName("I-1 — 리뷰 0건이면 rating 0.0 / reviewCount 0 (AVG가 NULL을 반환)")
    void zeroReviewsYieldZeroRating() {
        CandidateRow row = new CandidateRow(product(1L, 11L, 2L, 1000), 0L, null);
        when(productRepository.searchCandidates(any(), eq(false), any(), eq(false), any(),
                any(), any(), any())).thenReturn(List.of(row));
        lenient().when(categoryService.getNames(anyCollection())).thenReturn(Map.of(11L, "티셔츠"));
        lenient().when(brandService.getNames(anyCollection())).thenReturn(Map.of(2L, "브랜드"));

        ProductCandidateResponse candidate = productService.searchCandidates(
                null, null, null, null, null, null).get(0);

        assertThat(candidate.rating()).isEqualTo(0.0);
        assertThat(candidate.reviewCount()).isZero();
    }

    @Test
    @DisplayName("I-1 — 대분류명이면 하위 소분류 전체 포함 검색 (02 D20)")
    void rootCategoryIncludesChildren() {
        CandidateRow row = new CandidateRow(product(1L, 11L, 2L, 1000), 0L, null);
        when(categoryService.resolveIdsByName("패션")).thenReturn(Optional.of(List.of(11L, 12L)));
        when(productRepository.searchCandidates(any(), eq(true), eq(List.of(11L, 12L)),
                eq(false), any(), any(), any(), any())).thenReturn(List.of(row));
        lenient().when(categoryService.getNames(anyCollection())).thenReturn(Map.of(11L, "티셔츠"));
        lenient().when(brandService.getNames(anyCollection())).thenReturn(Map.of(2L, "브랜드"));

        List<ProductCandidateResponse> result = productService.searchCandidates(
                null, "패션", null, null, null, null);

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
