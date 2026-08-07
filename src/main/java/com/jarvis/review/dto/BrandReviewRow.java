package com.jarvis.review.dto;

import java.time.LocalDateTime;

/**
 * 브랜드 단위 후기 목록 프로젝션 (노션 I-31) — 상품 하나가 대상인 {@link ReviewRow}와 달리
 * 브랜드 전체가 대상이라 productId·productName이 함께 필요하다(에이전트가 상품별로 묶어 말하려면 필수).
 * authorNickname은 회원이면 member.nickname, 크롤링 리뷰(02 D19)면 review.author_name이다.
 *
 * <p>소비자는 현재 판매자 지표(I-31) 하나지만 소유자는 review다 — 후기 테이블의 모양을 아는 건
 * 후기 도메인이어야 하고, 반대로 두면 후기를 고칠 때마다 판매자가 딸려온다.
 */
public record BrandReviewRow(Long reviewId, Long productId, String productName, int rating,
                             String content, String authorNickname, LocalDateTime createdAt) {
}
