package com.jarvis.wishlist.dto;

import com.jarvis.global.response.StringId;

/** M-5 응답 — 찜한 productId (노션 명세, 2026-07-18 정합화) */
public record WishlistAddResponse(@StringId Long productId) {
}
