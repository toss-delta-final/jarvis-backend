package com.jarvis.wishlist.dto;

import jakarta.validation.constraints.NotNull;

/** M-5 (04 §5) */
public record WishlistAddRequest(@NotNull Long productId) {
}
