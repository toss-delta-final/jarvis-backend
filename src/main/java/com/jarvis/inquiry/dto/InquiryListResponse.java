package com.jarvis.inquiry.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jarvis.inquiry.Inquiry;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.data.domain.Page;

/** M-9 (04 §5) — 제목·내용·상태·답변. 날짜는 ISO 8601 + 오프셋 (03 D2) */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InquiryListResponse(List<Item> items, int page, int size,
                                  long totalElements, int totalPages) {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(Long id, String title, String content, String status,
                       String answer, OffsetDateTime answeredAt, OffsetDateTime createdAt) {

        public static Item from(Inquiry inquiry) {
            return new Item(inquiry.getId(), inquiry.getTitle(), inquiry.getContent(),
                    inquiry.getStatus().name(), inquiry.getAnswer(),
                    toOffset(inquiry.getAnsweredAt()), toOffset(inquiry.getCreatedAt()));
        }

        private static OffsetDateTime toOffset(LocalDateTime dateTime) {
            return dateTime == null ? null : dateTime.atZone(ZONE).toOffsetDateTime();
        }
    }

    public static InquiryListResponse from(Page<Inquiry> inquiries) {
        return new InquiryListResponse(inquiries.getContent().stream().map(Item::from).toList(),
                inquiries.getNumber(), inquiries.getSize(),
                inquiries.getTotalElements(), inquiries.getTotalPages());
    }
}
