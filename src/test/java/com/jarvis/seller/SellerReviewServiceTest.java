package com.jarvis.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jarvis.brand.BrandRepository;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.product.Product;
import com.jarvis.product.ProductRepository;
import com.jarvis.review.ReviewRepository;
import com.jarvis.seller.dto.SellerReviewListResponse;
import com.jarvis.review.dto.BrandReviewRow;
import com.jarvis.seller.dto.SellerReviewStatsResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/** I-31 (노션 I-31) — VISIBLE만·크롤링 리뷰 포함·stats 집계 규칙 */
@ExtendWith(MockitoExtension.class)
class SellerReviewServiceTest {

    private static final Long BRAND_ID = 7L;
    private static final AnalysisPeriod PERIOD =
            new AnalysisPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

    @Mock private ReviewRepository reviewRepository;
    @Mock private BrandRepository brandRepository;
    @Mock private ProductRepository productRepository;

    @InjectMocks private SellerReviewService service;

    private static BrandReviewRow row(long id, long productId, int rating, String nickname) {
        return new BrandReviewRow(id, productId, "여행용 파우치", rating, "지퍼가 고장났어요",
                nickname, LocalDateTime.of(2026, 7, 21, 12, 0));
    }

    private static Product product(long id, Long brandId) {
        Product p = mock(Product.class);
        lenient().when(p.getBrandId()).thenReturn(brandId);
        return p;
    }

    @Test
    @DisplayName("목록: 크롤링 리뷰의 authorNickname은 author_name으로 채워진다 (02 D19)")
    void listIncludesCrawledReviews() {
        List<BrandReviewRow> rows = List.of(row(7L, 3L, 2, "자비스"), row(8L, 3L, 5, "11번가구매자"));

        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        when(reviewRepository.findBrandReviewsLatest(eq(BRAND_ID), any(), anyBoolean(), any(),
                any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows, Pageable.unpaged(), 47));

        SellerReviewListResponse res = service.list(BRAND_ID, null, null, "latest", PERIOD, 20, 0);

        assertThat(res.total()).isEqualTo(47L);
        assertThat(res.rows()).hasSize(2);
        assertThat(res.rows().get(0).reviewId()).isEqualTo(7L);
        assertThat(res.rows().get(0).productName()).isEqualTo("여행용 파우치");
        assertThat(res.rows().get(1).authorNickname()).isEqualTo("11번가구매자");
    }

    @Test
    @DisplayName("stats: distribution은 5~1 키를 항상 전부 내리고 평균은 소수 1자리다")
    void statsFillsAllRatingKeys() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        // 5점 2건, 2점 1건만 존재 — 4·3·1점은 행이 없다
        when(reviewRepository.countBrandReviewsByRating(eq(BRAND_ID), any(), anyBoolean(), any(),
                any(), any())).thenReturn(List.<Object[]>of(new Object[]{5, 2L}, new Object[]{2, 1L}));
        when(reviewRepository.aggregateBrandReviewsByProduct(eq(BRAND_ID), any(), anyBoolean(), any(),
                any(), any())).thenReturn(List.<Object[]>of(new Object[]{3L, "여행용 파우치", 3L, 4.0}));

        SellerReviewStatsResponse res = service.stats(BRAND_ID, null, null, PERIOD);

        assertThat(res.totalCount()).isEqualTo(3L);
        assertThat(res.distribution()).containsExactly(
                java.util.Map.entry("5", 2L), java.util.Map.entry("4", 0L),
                java.util.Map.entry("3", 0L), java.util.Map.entry("2", 1L),
                java.util.Map.entry("1", 0L));
        assertThat(res.averageRating()).isEqualTo(4.0); // (5*2 + 2*1) / 3 = 4.0
        assertThat(res.byProduct()).hasSize(1);
        assertThat(res.byProduct().get(0).productId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("stats: 리뷰 0건이면 averageRating은 null이다 — 0이 아님 (I-16 규칙)")
    void statsAverageIsNullWhenEmpty() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        when(reviewRepository.countBrandReviewsByRating(eq(BRAND_ID), any(), anyBoolean(), any(),
                any(), any())).thenReturn(List.of());
        when(reviewRepository.aggregateBrandReviewsByProduct(eq(BRAND_ID), any(), anyBoolean(), any(),
                any(), any())).thenReturn(List.of());

        SellerReviewStatsResponse res = service.stats(BRAND_ID, null, null, PERIOD);

        assertThat(res.totalCount()).isZero();
        assertThat(res.averageRating()).isNull();
        assertThat(res.distribution()).containsValues(0L, 0L, 0L, 0L, 0L);
        assertThat(res.byProduct()).isEmpty();
    }

    @Test
    @DisplayName("타 브랜드·미존재 productId는 404 PRODUCT_NOT_FOUND로 존재를 은닉한다 (I-11 규칙)")
    void rejectsForeignProduct() {
        // mock은 지역변수로 먼저 만든다 — thenReturn(...) 인자 안에서 stub하면 스터빙이 중첩된다
        Product foreign = product(3L, 99L);

        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);
        when(productRepository.findById(3L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.list(BRAND_ID, 3L, null, "latest", PERIOD, 20, 0))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);

        when(productRepository.findById(4L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.list(BRAND_ID, 4L, null, "latest", PERIOD, 20, 0))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("없는 brandId는 404 BRAND_NOT_FOUND")
    void rejectsUnknownBrand() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.stats(BRAND_ID, null, null, PERIOD))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BRAND_NOT_FOUND);
    }

    @Test
    @DisplayName("rating이 1~5 밖이거나 숫자가 아니면 400 VALIDATION_ERROR")
    void rejectsInvalidRating() {
        when(brandRepository.existsById(BRAND_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.list(BRAND_ID, null, "0,2", "latest", PERIOD, 20, 0))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThatThrownBy(() -> service.list(BRAND_ID, null, "6", "latest", PERIOD, 20, 0))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThatThrownBy(() -> service.list(BRAND_ID, null, "별로", "latest", PERIOD, 20, 0))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("기간 생략 시 최근 7일 — to=오늘, from=6일 전 (노션 I-31)")
    void defaultPeriodIsLastSevenDays() {
        AnalysisPeriod period = AnalysisPeriod.withDefaultDays(null, null, 7);

        assertThat(period.to()).isEqualTo(LocalDate.now(java.time.ZoneId.of("Asia/Seoul")));
        assertThat(period.from()).isEqualTo(period.to().minusDays(6));

        // 한쪽만 주면 그 값을 기준으로 반대쪽을 채운다
        AnalysisPeriod toOnly = AnalysisPeriod.withDefaultDays(null, "2026-07-31", 7);
        assertThat(toOnly.from()).isEqualTo(LocalDate.of(2026, 7, 25));

        assertThatThrownBy(() -> AnalysisPeriod.withDefaultDays("2026-08-05", "2026-08-01", 7))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PERIOD);
    }
}
