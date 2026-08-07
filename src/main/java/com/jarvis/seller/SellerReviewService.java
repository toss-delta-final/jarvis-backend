package com.jarvis.seller;

import com.jarvis.brand.BrandRepository;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.product.Product;
import com.jarvis.product.ProductRepository;
import com.jarvis.review.ReviewRepository;
import com.jarvis.review.dto.BrandReviewRow;
import com.jarvis.seller.dto.SellerReviewListResponse;
import com.jarvis.seller.dto.SellerReviewStatsResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * I-31 자사 상품 리뷰 조회 (노션 I-31) — 판매자 챗봇의 읽기 전용 콜백이라 HITL이 없다.
 * status=VISIBLE만 반환한다: 신고로 숨겨진 리뷰를 에이전트가 인용하면 사고이므로 구매자
 * 노출(P-3)과 같은 진실만 보게 한다. 숨김 리뷰 열람은 admin 도메인 소관.
 * 크롤링 리뷰(member_id NULL, 02 D19)도 포함한다 — 구매자에게 P-3로 노출되고 있어
 * 판매자에게만 감출 이유가 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerReviewService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    /** distribution 키 — 없는 별점도 0으로 채워 항상 5개를 내린다(P-3와 같은 모양) */
    private static final List<Integer> RATING_KEYS = List.of(5, 4, 3, 2, 1);

    private final ReviewRepository reviewRepository;
    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;

    public SellerReviewListResponse list(Long brandId, Long productId, String rating,
                                         String sort, AnalysisPeriod period, int limit, int offset) {
        Filters f = filters(brandId, productId, rating, period);
        // 정렬은 쿼리에 박혀 있으므로 Pageable에는 Sort를 싣지 않는다(중복 order by 방지)
        Pageable pageable = new OffsetPageRequest(offset, limit, Sort.unsorted());
        Page<BrandReviewRow> page = "ratingAsc".equals(sort)
                ? reviewRepository.findBrandReviewsRatingAsc(brandId, productId, f.applyRating(),
                        f.ratings(), f.from(), f.to(), pageable)
                : reviewRepository.findBrandReviewsLatest(brandId, productId, f.applyRating(),
                        f.ratings(), f.from(), f.to(), pageable);
        return new SellerReviewListResponse(
                page.getContent().stream().map(SellerReviewService::toRow).toList(),
                page.getTotalElements());
    }

    public SellerReviewStatsResponse stats(Long brandId, Long productId, String rating,
                                           AnalysisPeriod period) {
        Filters f = filters(brandId, productId, rating, period);

        Map<Integer, Long> raw = reviewRepository.countBrandReviewsByRating(brandId, productId,
                        f.applyRating(), f.ratings(), f.from(), f.to()).stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> ((Number) row[0]).intValue(), row -> ((Number) row[1]).longValue()));
        Map<String, Long> distribution = new LinkedHashMap<>();
        RATING_KEYS.forEach(key -> distribution.put(String.valueOf(key), raw.getOrDefault(key, 0L)));

        long totalCount = distribution.values().stream().mapToLong(Long::longValue).sum();
        // 평균은 분포에서 되계산한다 — 같은 필터로 쿼리를 한 번 더 돌리지 않기 위해서다.
        Double averageRating = totalCount == 0 ? null : round1(
                (double) RATING_KEYS.stream().mapToLong(k -> k * raw.getOrDefault(k, 0L)).sum() / totalCount);

        List<SellerReviewStatsResponse.ByProduct> byProduct = reviewRepository
                .aggregateBrandReviewsByProduct(brandId, productId, f.applyRating(), f.ratings(),
                        f.from(), f.to()).stream()
                .map(row -> new SellerReviewStatsResponse.ByProduct(
                        ((Number) row[0]).longValue(), (String) row[1],
                        ((Number) row[2]).longValue(), round1(((Number) row[3]).doubleValue())))
                .toList();

        return new SellerReviewStatsResponse(totalCount, averageRating, distribution, byProduct);
    }

    /** 브랜드·상품 소유권 확인 + rating 파싱 + 기간 경계 — 두 모드가 같은 규칙을 쓴다 */
    private Filters filters(Long brandId, Long productId, String rating, AnalysisPeriod period) {
        if (!brandRepository.existsById(brandId)) {
            throw new BusinessException(ErrorCode.BRAND_NOT_FOUND);
        }
        if (productId != null) {
            // 타 브랜드 상품은 404로 존재를 은닉한다(I-11 규칙) — 403이 아니다
            Optional<Product> product = productRepository.findById(productId);
            if (product.isEmpty() || !brandId.equals(product.get().getBrandId())) {
                throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
            }
        }
        List<Integer> ratings = parseRatings(rating);
        return new Filters(!ratings.isEmpty(), ratings.isEmpty() ? List.of(0) : ratings,
                period.from().atStartOfDay(),
                // to는 그날을 포함해야 하므로 다음날 0시 미만으로 연다
                period.to().plusDays(1).atStartOfDay());
    }

    /** "1,2" → [1, 2]. 1–5 밖이거나 숫자가 아니면 VALIDATION_ERROR (노션 I-31) */
    private static List<Integer> parseRatings(String rating) {
        if (rating == null || rating.isBlank()) {
            return List.of();
        }
        try {
            List<Integer> parsed = Arrays.stream(rating.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .map(Integer::parseInt)
                    .toList();
            if (parsed.stream().anyMatch(r -> r < 1 || r > 5)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    private static SellerReviewListResponse.Row toRow(BrandReviewRow row) {
        return new SellerReviewListResponse.Row(row.reviewId(), row.productId(), row.productName(),
                row.rating(), row.content(), row.authorNickname(),
                row.createdAt().atZone(ZONE).toOffsetDateTime());
    }

    private static Double round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    /** applyRating이 false면 ratings는 쓰이지 않는다 — 빈 IN을 피하려는 플레이스홀더다 */
    private record Filters(boolean applyRating, List<Integer> ratings,
                           LocalDateTime from, LocalDateTime to) {
    }
}
