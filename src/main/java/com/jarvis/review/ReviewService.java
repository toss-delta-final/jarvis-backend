package com.jarvis.review;

import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.order.OrderItem;
import com.jarvis.order.OrderItemRepository;
import com.jarvis.order.OrderRepository;
import com.jarvis.review.dto.RatingStats;
import com.jarvis.review.dto.ReviewCreateRequest;
import com.jarvis.review.dto.ReviewCreateResponse;
import com.jarvis.review.dto.ReviewListResponse;
import com.jarvis.review.dto.ReviewRow;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P-3 목록·P-2 통계 + M-1 작성·M-3 신고 (04 §5).
 * 조회는 상품 존재 검증을 하지 않는다(미존재 productId → 빈 목록) —
 * ProductService가 이 서비스에 의존(P-2 통계)하므로 역방향 의존을 만들지 않기 위함 (03 §3-1 순환 방지).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ReviewReportRepository reviewReportRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderRepository orderRepository;

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
                item.getId(), item.getProductId(), memberId, request.rating(), request.content()));
        return ReviewCreateResponse.from(review);
    }

    /** M-3 — 자기 후기 400, 중복 신고 409 (02 D29) */
    @Transactional
    public void report(Long memberId, Long reviewId, String reason) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_NOT_FOUND));
        if (memberId.equals(review.getMemberId())) {
            throw new BusinessException(ErrorCode.REVIEW_SELF_REPORT);
        }
        if (reviewReportRepository.existsByReviewIdAndReporterId(reviewId, memberId)) {
            throw new BusinessException(ErrorCode.REVIEW_REPORT_DUPLICATE);
        }
        reviewReportRepository.save(ReviewReport.request(reviewId, memberId, reason));
    }

    public ReviewListResponse getProductReviews(Long productId, int page, int size, String sort) {
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

    /** 상품별 평점 통계 배치 조회 — P-2 상세·카드 조립용 (02 D9) */
    public Map<Long, RatingStats> getStats(Collection<Long> productIds) {
        Map<Long, RatingStats> stats = new HashMap<>();
        if (productIds.isEmpty()) {
            return stats;
        }
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
