package com.jarvis.review.dto;

import java.time.LocalDateTime;

/** P-3 목록 프로젝션 — memberNickname은 회원 리뷰만, 크롤링 리뷰는 authorName (02 D19) */
public record ReviewRow(Long id, int rating, String content,
                        String authorName, String memberNickname, LocalDateTime createdAt) {
}
