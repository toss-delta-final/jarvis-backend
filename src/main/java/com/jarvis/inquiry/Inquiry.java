package com.jarvis.inquiry;

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
 * M-9 조회 전용 (04 §5) — 접수는 사용자 API가 없다: 문의 챗봇(LLM)이 internal 콜백으로만
 * 생성(문의 단일 채널 원칙, 05 문서). 제목·내용은 LLM 생성 (02 D23). 생성 API는 Phase 5.
 */
@Entity
@Table(name = "inquiry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inquiry extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InquiryStatus status;

    @Column(columnDefinition = "text")
    private String answer;

    /** 답변 관리자 (고도화) */
    @Column(name = "answered_by")
    private Long answeredBy;

    @Column(name = "answered_at")
    private LocalDateTime answeredAt;

    /** I-5 접수 (05 §I-5) — 제목·내용은 LLM 생성 (02 D23) */
    public static Inquiry receive(Long memberId, String title, String content) {
        Inquiry inquiry = new Inquiry();
        inquiry.memberId = memberId;
        inquiry.title = title;
        inquiry.content = content;
        inquiry.status = InquiryStatus.PENDING;
        return inquiry;
    }
}
