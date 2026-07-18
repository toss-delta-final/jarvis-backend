package com.jarvis.review;

import com.jarvis.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * M-3 후기 신고 (02 §3) — UNIQUE(review_id, reporter_id)로 중복 신고 방지.
 * 신고 처리(숨김)는 review.status 변경으로 — 고도화 (02 D4).
 */
@Entity
@Table(name = "review_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewReport extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_id", nullable = false)
    private Long reviewId;

    @Column(name = "reporter_id", nullable = false)
    private Long reporterId;

    @Column(nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewReportStatus status;

    /** 처리 관리자 — MVP는 미처리라 항상 NULL */
    @Column(name = "processed_by")
    private Long processedBy;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    public static ReviewReport request(Long reviewId, Long reporterId, String reason) {
        ReviewReport report = new ReviewReport();
        report.reviewId = reviewId;
        report.reporterId = reporterId;
        report.reason = reason;
        report.status = ReviewReportStatus.PENDING;
        return report;
    }
}
