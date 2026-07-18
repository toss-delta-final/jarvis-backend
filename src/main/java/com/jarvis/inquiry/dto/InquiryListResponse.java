package com.jarvis.inquiry.dto;

import com.jarvis.inquiry.Inquiry;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.data.domain.Page;

/** M-9 (04 §5) — 제목·내용·상태·답변. 날짜는 ISO 8601 + 오프셋 (03 D2) */
public record InquiryListResponse(List<Item> content, int page, int size,
                                  long totalElements, int totalPages) {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** 답변 전이면 answer는 명시적 null (Notion 계약) */
    public record Item(Long inquiryId, String title, String content, String status,
                       Answer answer, OffsetDateTime createdAt) {

        public static Item from(Inquiry inquiry) {
            Answer answer = inquiry.getAnswer() == null ? null
                    : new Answer(inquiry.getAnswer(), toOffset(inquiry.getAnsweredAt()));
            return new Item(inquiry.getId(), inquiry.getTitle(), inquiry.getContent(),
                    inquiry.getStatus().name(), answer, toOffset(inquiry.getCreatedAt()));
        }

        private static OffsetDateTime toOffset(LocalDateTime dateTime) {
            return dateTime == null ? null : dateTime.atZone(ZONE).toOffsetDateTime();
        }
    }

    public record Answer(String content, OffsetDateTime answeredAt) {
    }

    public static InquiryListResponse from(Page<Inquiry> inquiries) {
        return new InquiryListResponse(inquiries.getContent().stream().map(Item::from).toList(),
                inquiries.getNumber(), inquiries.getSize(),
                inquiries.getTotalElements(), inquiries.getTotalPages());
    }
}
