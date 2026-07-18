package com.jarvis.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.data.domain.Page;

/**
 * P-3 — page=0에만 distribution(별점 분포) 포함, page≥1은 생략(FE가 0페이지 값 재사용 — 04 §2).
 * 날짜는 ISO 8601 + 오프셋 (03 D2).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewListResponse(java.util.List<Item> items, int page, int size,
                                 long totalElements, int totalPages,
                                 Map<String, Long> distribution) {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    public record Item(Long id, int rating, String content, String authorName,
                       OffsetDateTime createdAt) {

        public static Item from(ReviewRow row) {
            String displayName = row.authorName() != null ? row.authorName()
                    : (row.memberNickname() != null ? row.memberNickname() : "알 수 없음");
            return new Item(row.id(), row.rating(), row.content(), displayName,
                    row.createdAt().atZone(ZONE).toOffsetDateTime());
        }
    }

    public static ReviewListResponse from(Page<ReviewRow> rows, Map<String, Long> distribution) {
        return new ReviewListResponse(rows.getContent().stream().map(Item::from).toList(),
                rows.getNumber(), rows.getSize(), rows.getTotalElements(), rows.getTotalPages(),
                distribution);
    }
}
