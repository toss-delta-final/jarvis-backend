package com.jarvis.category.dto;

import java.util.List;

/** P-1 — data.categories 래퍼 (노션 명세 정합화, 2026-07-18) */
public record CategoryListResponse(List<CategoryTreeResponse> categories) {
}
