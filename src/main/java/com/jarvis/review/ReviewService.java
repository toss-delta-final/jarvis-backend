package com.jarvis.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.jarvis.global.cache.RedisCache;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.order.OrderItem;
import com.jarvis.order.OrderItemRepository;
import com.jarvis.order.OrderRepository;
import com.jarvis.product.ProductRepository;
import com.jarvis.review.dto.RatingStats;
import com.jarvis.review.dto.ReviewCreateRequest;
import com.jarvis.review.dto.ReviewCreateResponse;
import com.jarvis.review.dto.ReviewListResponse;
import com.jarvis.review.dto.ReviewReportResponse;
import com.jarvis.review.dto.ReviewRow;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P-3 목록·P-2 통계 + M-1 작성·M-3 신고 (04 §5).
 * 조회의 상품 존재 검증은 ProductRepository로 직접 — ProductService가 이 서비스에 의존(P-2 통계)하므로
 * 역방향 의존을 만들지 않기 위함 (03 §3-1 순환 방지).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewService {

    /** 07 §2 키 지도 — 상품 단위 평점 사본. 무효화는 리뷰 작성 evict가 주 수단, TTL은 안전망 */
    private static final String STATS_KEY_PREFIX = "v1:review:stats:";
    private static final Duration STATS_TTL = Duration.ofHours(1);

    private final ReviewRepository reviewRepository;
    private final ReviewReportRepository reviewReportRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final RedisCache cache;

    /** M-1 — 자격: 본인 주문 + DELIVERED/CONFIRMED + 미작성 (01 §3, 02 D4) */
    @Transactional
    public ReviewCreateResponse write(Long memberId, ReviewCreateRequest request) {
        OrderItem item = orderItemRepository.findById(request.orderItemId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_ITEM_NOT_FOUND));
        // 남의 아이템은 존재를 노출하지 않고 404 (ClaimService와 동일 규약)
        orderRepository.findById(item.getOrderId())
                .filter(order -> order.getMemberId().equals(memberId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_ITEM_NOT_FOUND));
        if (!item.getStatus().canReview()) {
            throw new BusinessException(ErrorCode.REVIEW_NOT_ALLOWED);
        }
        if (reviewRepository.existsByOrderItemId(item.getId())) {
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_EXISTS);
        }
        Review review = reviewRepository.save(Review.write(
                item.getId(), item.getProductId(), memberId, request.rating(),
                request.normalizedContent()));
        cache.evictAfterCommit(STATS_KEY_PREFIX + item.getProductId());
        return ReviewCreateResponse.from(review);
    }

    /** M-3 — 자기 후기 400, 중복 신고 409 (02 D29) */
    @Transactional
    public ReviewReportResponse report(Long memberId, Long reviewId, String reason) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_NOT_FOUND));
        if (memberId.equals(review.getMemberId())) {
            throw new BusinessException(ErrorCode.REVIEW_SELF_REPORT);
        }
        if (reviewReportRepository.existsByReviewIdAndReporterId(reviewId, memberId)) {
            throw new BusinessException(ErrorCode.REVIEW_REPORT_DUPLICATE);
        }
        ReviewReport report = reviewReportRepository.save(
                ReviewReport.request(reviewId, memberId, reason));
        return new ReviewReportResponse(report.getId());
    }

    public ReviewListResponse getProductReviews(Long productId, int page, int size, String sort) {
        if (!productRepository.existsById(productId)) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        Pageable pageable = PageRequest.of(page, size);
        Page<ReviewRow> rows = "rating".equals(sort)
                ? reviewRepository.findVisibleRowsByRating(productId, pageable)
                : reviewRepository.findVisibleRowsLatest(productId, pageable);
        Map<String, Long> distribution = page == 0 ? getDistribution(productId) : null;
        return ReviewListResponse.from(rows, distribution);
    }

    /** 5→1 키 고정, 리뷰 0개면 0값 채운 객체 (04 §2) */
    private Map<String, Long> getDistribution(Long productId) {
        Map<Integer, Long> counted = new HashMap<>();
        for (Object[] row : reviewRepository.countVisibleByRating(productId)) {
            counted.put(((Number) row[0]).intValue(), ((Number) row[1]).longValue());
        }
        Map<String, Long> distribution = new LinkedHashMap<>();
        for (int rating = 5; rating >= 1; rating--) {
            distribution.put(String.valueOf(rating), counted.getOrDefault(rating, 0L));
        }
        return distribution;
    }

    /**
     * 상품별 평점 통계 배치 조회 — P-2 상세·카드 조립용 (02 D9).
     *
     * <p>캐시를 얹는다(07 §3-1 — 2026-08-10 부하 실측 후). 카드가 나가는 모든 화면이 이 집계를
     * 부르므로 적중 시 카드 조립의 DB 왕복 하나가 통째로 사라진다. D9의 반정규화 기각과 충돌하지
     * 않는다 — 컬럼은 어긋나도 영원히 조용하지만 이 사본은 TTL이 어긋남의 상한이고, 쓰기 경로가
     * 리뷰 작성 하나뿐이라({@link #write} — 숨김·삭제 API 없음) evict 한 곳으로 전 경로가 덮인다.
     * 리뷰 0개 상품도 EMPTY로 캐시된다 — 집계 결과에 행이 없다고 매번 DB를 되짚지 않게.
     */
    public Map<Long, RatingStats> getStats(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return cache.getAll(STATS_KEY_PREFIX, productIds, STATS_TTL,
                new TypeReference<RatingStats>() { }, this::loadStats, RatingStats.EMPTY);
    }

    private Map<Long, RatingStats> loadStats(List<Long> productIds) {
        Map<Long, RatingStats> stats = new HashMap<>();
        for (Object[] row : reviewRepository.aggregateVisibleByProductIds(productIds)) {
            stats.put(((Number) row[0]).longValue(),
                    RatingStats.of(((Number) row[1]).longValue(), (Double) row[2]));
        }
        return stats;
    }

    public RatingStats getStats(Long productId) {
        return getStats(java.util.List.of(productId)).getOrDefault(productId, RatingStats.EMPTY);
    }
}
