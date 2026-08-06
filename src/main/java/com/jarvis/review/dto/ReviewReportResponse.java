package com.jarvis.review.dto;

import com.jarvis.global.response.StringId;

/** M-3 응답 — 생성된 신고 id (노션 명세, 2026-07-18 정합화) */
public record ReviewReportResponse(@StringId Long reportId) {
}
