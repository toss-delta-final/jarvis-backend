package com.jarvis.product;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;

/** I-1 후보 조회 (05 §I-1) */
@ExtendWith(MockitoExtension.class)
class ProductCandidateSearchTest {

    @Mock ProductRepository productRepository;
    @Mock ProductOptionRepository productOptionRepository;
    @Mock ProductStockRepository productStockRepository;
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

    /** 색상 조건은 SQL이 판정하므로 여기선 "리포지토리에 무엇을 넘기는가"만 검증한다 */
    private String capturedColorPattern(List<String> colors) {
        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
        when(productRepository.searchCandidates(any(), eq(false), any(), eq(false), any(),
                any(), any(), pattern.capture())).thenReturn(List.of());

        productService.searchCandidates(null, null, null, null, null, colors);

        return pattern.getValue();
    }

    @Test
    @DisplayName("I-1 — 복수 색상은 정규식 하나로 합쳐 넘긴다 (2026-08-03)")
    void multipleColorsBecomeAlternationPattern() {
        assertThat(capturedColorPattern(List.of("네이비", "블랙"))).isEqualTo("네이비|블랙");
    }

    @Test
    @DisplayName("I-1 — 색상명의 정규식 메타문자는 이스케이프한다 (패턴 주입 차단)")
    void colorPatternEscapesRegexMeta() {
        // 이스케이프가 없으면 ".*"가 모든 상품을 통과시켜 색상 필터가 무력화된다
        assertThat(capturedColorPattern(List.of(".*"))).isEqualTo("\\.\\*");
        assertThat(capturedColorPattern(List.of("(블랙"))).isEqualTo("\\(블랙");
    }

    @Test
    @DisplayName("I-1 — 색상 미지정·공백뿐이면 패턴 null (조건 자체를 걸지 않는다)")
    void blankColorsYieldNullPattern() {
        assertThat(capturedColorPattern(null)).isNull();
        assertThat(capturedColorPattern(List.of("  ", ""))).isNull();
    }

    @Test
    @DisplayName("I-1 — 옵션은 20개까지만 싣고 optionCount는 전체 개수 (05 §I-1)")
    void optionsAreCappedWithFullCount() {
        CandidateRow row = new CandidateRow(product(1L, 11L, 2L, 1000), 0L, null);
        when(productRepository.searchCandidates(any(), eq(false), any(), eq(false), any(),
                any(), any(), any())).thenReturn(List.of(row));
        lenient().when(categoryService.getNames(anyCollection())).thenReturn(Map.of(11L, "티셔츠"));
        lenient().when(brandService.getNames(anyCollection())).thenReturn(Map.of(2L, "브랜드"));
        // 목 생성을 스터빙 밖에서 끝낸다 — thenReturn 인자 안에서 만들면 스터빙이 중첩돼 깨진다
        List<ProductOption> options =
                LongStream.rangeClosed(1, 25).mapToObj(i -> option(1L, i, "색상" + i)).toList();
        when(productOptionRepository.findAllByProductIdInOrderByProductIdAscIdAsc(List.of(1L)))
                .thenReturn(options);
        stubPurchasable(1L, LongStream.rangeClosed(1, 25).boxed().toList());

        ProductCandidateResponse candidate = productService.searchCandidates(
                null, null, null, null, null, null).get(0);

        assertThat(candidate.options()).hasSize(20).first().isEqualTo("색상1");
        assertThat(candidate.optionCount()).isEqualTo(25);
    }

    @Test
    @DisplayName("I-1 — 옵션 없는 상품은 빈 배열 + optionCount 0")
    void productWithoutOptions() {
        CandidateRow row = new CandidateRow(product(1L, 11L, 2L, 1000), 0L, null);
        when(productRepository.searchCandidates(any(), eq(false), any(), eq(false), any(),
                any(), any(), any())).thenReturn(List.of(row));
        lenient().when(categoryService.getNames(anyCollection())).thenReturn(Map.of(11L, "티셔츠"));
        lenient().when(brandService.getNames(anyCollection())).thenReturn(Map.of(2L, "브랜드"));
        when(productOptionRepository.findAllByProductIdInOrderByProductIdAscIdAsc(List.of(1L)))
                .thenReturn(List.of());
        stubPurchasable(1L, List.of());

        ProductCandidateResponse candidate = productService.searchCandidates(
                null, null, null, null, null, null).get(0);

        assertThat(candidate.options()).isEmpty();
        assertThat(candidate.optionCount()).isZero();
    }

    @Test
    @DisplayName("I-1 — 품절 옵션은 options에서 빠지고 optionCount도 구매 가능한 것만 센다 (2026-08-09)")
    void soldOutOptionsAreExcluded() {
        CandidateRow row = new CandidateRow(product(1L, 11L, 2L, 1000), 0L, null);
        when(productRepository.searchCandidates(any(), eq(false), any(), eq(false), any(),
                any(), any(), any())).thenReturn(List.of(row));
        lenient().when(categoryService.getNames(anyCollection())).thenReturn(Map.of(11L, "티셔츠"));
        lenient().when(brandService.getNames(anyCollection())).thenReturn(Map.of(2L, "브랜드"));
        // 목 생성을 스터빙 밖에서 끝낸다 — thenReturn 인자 안에서 만들면 스터빙이 중첩돼 깨진다
        List<ProductOption> options =
                List.of(option(1L, 10L, "S"), option(1L, 11L, "M"), option(1L, 12L, "L"));
        when(productOptionRepository.findAllByProductIdInOrderByProductIdAscIdAsc(List.of(1L)))
                .thenReturn(options);
        // M(11L)만 품절
        when(productStockRepository.findAllByProductIdIn(List.of(1L))).thenReturn(List.of(
                ProductStock.of(1L, 10L, 5), ProductStock.of(1L, 11L, 0), ProductStock.of(1L, 12L, 3)));

        ProductCandidateResponse candidate = productService.searchCandidates(
                null, null, null, null, null, null).get(0);

        assertThat(candidate.options()).containsExactly("S", "L");
        // 20개 상한에 안 걸렸으므로 optionCount == options.size() — 여기가 어긋나면 AI의 정합 가드가
        // "잘렸다"로 오판해 자동 선택을 포기한다
        assertThat(candidate.optionCount()).isEqualTo(2);
    }

    private static ProductOption option(long productId, long optionId, String name) {
        ProductOption option = mock(ProductOption.class, withSettings().strictness(Strictness.LENIENT));
        when(option.getId()).thenReturn(optionId);
        when(option.getProductId()).thenReturn(productId);
        when(option.getName()).thenReturn(name);
        return option;
    }

    /** 재고가 남은 옵션들 — I-1은 여기 없는 옵션을 후보에서 뺀다 (2026-08-09) */
    private void stubPurchasable(long productId, java.util.List<Long> optionIds) {
        when(productStockRepository.findAllByProductIdIn(java.util.List.of(productId)))
                .thenReturn(optionIds.stream()
                        .map(id -> ProductStock.of(productId, id, 10))
                        .toList());
    }
}
