package com.jarvis.review;

/** 신고 처리 상태 (02 §3) — 처리(HIDE)는 고도화, MVP는 PENDING 접수만 */
public enum ReviewReportStatus {
    PENDING, IN_PROGRESS, DONE
}
