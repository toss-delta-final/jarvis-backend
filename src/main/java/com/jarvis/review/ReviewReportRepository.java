package com.jarvis.review;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewReportRepository extends JpaRepository<ReviewReport, Long> {

    /** 중복 신고 409 — UNIQUE(review_id, reporter_id)의 선제 검증 (04 §5 M-3) */
    boolean existsByReviewIdAndReporterId(Long reviewId, Long reporterId);
}
