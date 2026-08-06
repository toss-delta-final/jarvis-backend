package com.jarvis.seller.dto;

import java.time.LocalDateTime;

/**
 * I-31 목록 프로젝션 (노션 I-31) — P-3의 ReviewRow와 달리 브랜드 전체가 대상이라
 * productId·productName이 함께 필요하다(에이전트가 상품별로 묶어 말하려면 필수).
 * authorNickname은 회원이면 member.nickname, 크롤링 리뷰(02 D19)면 review.author_name이다.
 */
public record SellerReviewRow(Long reviewId, Long productId, String productName, int rating,
                              String content, String authorNickname, LocalDateTime createdAt) {
}
