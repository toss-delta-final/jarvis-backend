package com.jarvis.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.order.Order;
import com.jarvis.order.OrderItem;
import com.jarvis.order.OrderItemRepository;
import com.jarvis.order.OrderItemStatus;
import com.jarvis.order.OrderRepository;
import com.jarvis.review.dto.ReviewCreateRequest;
import com.jarvis.review.dto.ReviewCreateResponse;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** M-1 후기 작성 + M-3 후기 신고 (04 §5, 01 §3) */
@ExtendWith(MockitoExtension.class)
class ReviewWriteServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long ORDER_ITEM_ID = 100L;

    @Mock ReviewRepository reviewRepository;
    @Mock ReviewReportRepository reviewReportRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock OrderRepository orderRepository;

    @InjectMocks ReviewService reviewService;

    private OrderItem orderItem;
    private Order order;

    @BeforeEach
    void setUp() {
        orderItem = mock(OrderItem.class, withSettings().lenient());
        when(orderItem.getId()).thenReturn(ORDER_ITEM_ID);
        when(orderItem.getOrderId()).thenReturn(50L);
        when(orderItem.getProductId()).thenReturn(10L);
        when(orderItem.getStatus()).thenReturn(OrderItemStatus.DELIVERED);
        order = mock(Order.class, withSettings().lenient());
        when(order.getMemberId()).thenReturn(MEMBER_ID);
        lenient().when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));
        lenient().when(orderRepository.findById(50L)).thenReturn(Optional.of(order));
        lenient().when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> {
            Review review = inv.getArgument(0);
            ReflectionTestUtils.setField(review, "id", 7L);
            ReflectionTestUtils.setField(review, "createdAt", LocalDateTime.now());
            return review;
        });
    }

    @Test
    @DisplayName("M-1 — DELIVERED 아이템 후기 작성 성공: VISIBLE + 아이템의 productId 스냅샷")
    void writeOnDelivered() {
        ReviewCreateResponse response = reviewService.write(MEMBER_ID,
                new ReviewCreateRequest(ORDER_ITEM_ID, 5, "좋아요"));

        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).save(captor.capture());
        Review saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ReviewStatus.VISIBLE);
        assertThat(saved.getProductId()).isEqualTo(10L);
        assertThat(saved.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(saved.getOrderItemId()).isEqualTo(ORDER_ITEM_ID);
        assertThat(response.id()).isEqualTo(7L);
    }

    @Test
    @DisplayName("M-1 — CONFIRMED에서도 작성 가능 (01 D8)")
    void writeOnConfirmed() {
        when(orderItem.getStatus()).thenReturn(OrderItemStatus.CONFIRMED);

        reviewService.write(MEMBER_ID, new ReviewCreateRequest(ORDER_ITEM_ID, 4, "잘 쓰고 있어요"));

        verify(reviewRepository).save(any(Review.class));
    }

    @Test
    @DisplayName("M-1 — SHIPPING 등 자격 외 상태는 400 REVIEW_NOT_ALLOWED")
    void writeRejectedByStatus() {
        when(orderItem.getStatus()).thenReturn(OrderItemStatus.SHIPPING);

        assertThatThrownBy(() -> reviewService.write(MEMBER_ID,
                new ReviewCreateRequest(ORDER_ITEM_ID, 5, "아직 배송 중")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REVIEW_NOT_ALLOWED);
    }

    @Test
    @DisplayName("M-1 — 이미 작성한 아이템은 409 REVIEW_ALREADY_EXISTS")
    void writeDuplicate() {
        when(reviewRepository.existsByOrderItemId(ORDER_ITEM_ID)).thenReturn(true);

        assertThatThrownBy(() -> reviewService.write(MEMBER_ID,
                new ReviewCreateRequest(ORDER_ITEM_ID, 5, "또 쓰기")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REVIEW_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("M-1 — 남의 주문 아이템은 404 ORDER_ITEM_NOT_FOUND (존재 노출 안 함)")
    void writeNotOwner() {
        when(order.getMemberId()).thenReturn(999L);

        assertThatThrownBy(() -> reviewService.write(MEMBER_ID,
                new ReviewCreateRequest(ORDER_ITEM_ID, 5, "남의 것")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_ITEM_NOT_FOUND);
    }

    @Test
    @DisplayName("M-3 — 신고 성공: PENDING으로 저장")
    void reportSaved() {
        Review review = mock(Review.class);
        when(review.getMemberId()).thenReturn(999L);
        when(reviewRepository.findById(7L)).thenReturn(Optional.of(review));

        reviewService.report(MEMBER_ID, 7L, "부적절한 내용");

        ArgumentCaptor<ReviewReport> captor = ArgumentCaptor.forClass(ReviewReport.class);
        verify(reviewReportRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ReviewReportStatus.PENDING);
        assertThat(captor.getValue().getReporterId()).isEqualTo(MEMBER_ID);
    }

    @Test
    @DisplayName("M-3 — 자기 후기 신고는 400 REVIEW_SELF_REPORT (02 D29)")
    void reportSelf() {
        Review review = mock(Review.class);
        when(review.getMemberId()).thenReturn(MEMBER_ID);
        when(reviewRepository.findById(7L)).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> reviewService.report(MEMBER_ID, 7L, "내 후기"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REVIEW_SELF_REPORT);
    }

    @Test
    @DisplayName("M-3 — 중복 신고는 409 REVIEW_REPORT_DUPLICATE")
    void reportDuplicate() {
        Review review = mock(Review.class);
        when(review.getMemberId()).thenReturn(999L);
        when(reviewRepository.findById(7L)).thenReturn(Optional.of(review));
        when(reviewReportRepository.existsByReviewIdAndReporterId(7L, MEMBER_ID)).thenReturn(true);

        assertThatThrownBy(() -> reviewService.report(MEMBER_ID, 7L, "두 번째 신고"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REVIEW_REPORT_DUPLICATE);
    }

    @Test
    @DisplayName("M-3 — 없는 후기는 404 REVIEW_NOT_FOUND")
    void reportMissingReview() {
        when(reviewRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.report(MEMBER_ID, 404L, "유령 후기"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REVIEW_NOT_FOUND);
    }
}
